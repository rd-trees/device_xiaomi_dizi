/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Forward the Smart Pen's pressure to the NT36532 touch driver, and decide pen contact from it.
//
// The pen measures pressure itself and sends it over Bluetooth as HID input
// report 5. The touch driver takes the pen's pressure from /dev/mipp_pen0
// (nvt_pen_fops_write: [0] = 0x05, [1..2] = pressure LE, [9..14] = timestamp)
// and otherwise reports a fixed 0x19A. HyperOS's Bluetooth stack writes report 5
// there unchanged (bta_hh_co_data -> write_pressure_by_fd); AOSP's stack hands
// it to uhid instead, so we read it back from the pen's hidraw node.
//
// Contact: the driver reports BTN_TOUCH whenever the IC says "ink" (distance 0) and the
// pressure is non-zero, and on every hover -> ink switch it forces 0x19A for a frame. With
// this pen the IC says ink as soon as the pen comes close, and the pen's sensor never rests at
// zero (up to ~250 while held or hovering), so hovering produced taps. We therefore grab the
// IC's pen input device and re-publish it through uinput: contact = IC ink and Bluetooth
// pressure above a dead zone, everything else is hover. If this process dies, the grab ends
// and the IC's own device takes over again.

#include <dirent.h>
#include <fcntl.h>
#include <linux/hidraw.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include <android-base/logging.h>
#include <android-base/unique_fd.h>

using android::base::unique_fd;

namespace {

constexpr const char* kMippDev = "/dev/mipp_pen0";
constexpr const char* kUinputDev = "/dev/uinput";
constexpr const char* kIcPenName = "NVTCapacitivePen";
constexpr const char* kVirtualPenName = "NVTCapacitivePen (dizi)";
constexpr uint8_t kPressureReportId = 0x05;
constexpr size_t kMippDataLength = 15;  // MIPP_PEN_DATA_LENGTH

// Bluetooth HID identifiers (PnP ID): Redmi Smart Pen, POCO Smart Pen.
// Keep in sync with DiziPen's PenMonitor.
constexpr uint16_t kPenVendorId = 0x0022;
constexpr uint16_t kPenProductIds[] = {0x4e83, 0x3283};

constexpr int kRescanMs = 1000;

// Hover peaks seen: ~255. Light taps start at ~340. Overridable as argv[1] for tuning.
constexpr unsigned kDefaultDeadZone = 280;

constexpr int kAbsAxes[] = {ABS_X, ABS_Y, ABS_PRESSURE, ABS_DISTANCE, ABS_TILT_X, ABS_TILT_Y};

bool isPen(const hidraw_devinfo& info) {
    if (info.bustype != BUS_BLUETOOTH || static_cast<uint16_t>(info.vendor) != kPenVendorId) {
        return false;
    }
    for (uint16_t product : kPenProductIds) {
        if (static_cast<uint16_t>(info.product) == product) {
            return true;
        }
    }
    return false;
}

unique_fd openPenHidraw() {
    std::unique_ptr<DIR, decltype(&closedir)> dir(opendir("/dev"), closedir);
    if (!dir) {
        PLOG(ERROR) << "opendir /dev";
        return {};
    }
    while (dirent* entry = readdir(dir.get())) {
        if (strncmp(entry->d_name, "hidraw", 6) != 0) {
            continue;
        }
        std::string path = std::string("/dev/") + entry->d_name;
        unique_fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
        if (fd < 0) {
            continue;
        }
        hidraw_devinfo info = {};
        if (ioctl(fd, HIDIOCGRAWINFO, &info) == 0 && isPen(info)) {
            LOG(INFO) << "pen pressure: reading " << path;
            return fd;
        }
    }
    return {};
}

unique_fd openIcPen() {
    std::unique_ptr<DIR, decltype(&closedir)> dir(opendir("/dev/input"), closedir);
    if (!dir) {
        PLOG(ERROR) << "opendir /dev/input";
        return {};
    }
    while (dirent* entry = readdir(dir.get())) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }
        std::string path = std::string("/dev/input/") + entry->d_name;
        unique_fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC));
        if (fd < 0) {
            continue;
        }
        char name[64] = {};
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) >= 0 && !strcmp(name, kIcPenName)) {
            LOG(INFO) << "pen contact: using " << path;
            return fd;
        }
    }
    return {};
}

// The IC's pen as last reported, plus the Bluetooth pressure.
struct PenState {
    int x = 0, y = 0, tiltX = 0, tiltY = 0, distance = 0;
    bool inRange = false, button1 = false, button2 = false;
    unsigned pressure = 0;  // Bluetooth pressure minus the dead zone
};

class VirtualPen {
  public:
    // Mirrors the IC's pen device; on failure the IC's own device stays in use.
    bool create(int icFd) {
        fd_.reset(open(kUinputDev, O_WRONLY | O_CLOEXEC));
        if (fd_ < 0) {
            PLOG(ERROR) << "open " << kUinputDev;
            return false;
        }
        input_id id = {};
        ioctl(icFd, EVIOCGID, &id);
        bool ok = ioctl(fd_, UI_SET_EVBIT, EV_KEY) == 0 && ioctl(fd_, UI_SET_EVBIT, EV_ABS) == 0 &&
                  ioctl(fd_, UI_SET_PROPBIT, INPUT_PROP_DIRECT) == 0;
        for (int key : {BTN_TOOL_PEN, BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2}) {
            ok = ok && ioctl(fd_, UI_SET_KEYBIT, key) == 0;
        }
        for (int axis : kAbsAxes) {
            uinput_abs_setup abs = {};
            abs.code = axis;
            if (ioctl(icFd, EVIOCGABS(axis), &abs.absinfo) < 0) {
                PLOG(ERROR) << "EVIOCGABS " << axis;
                return false;
            }
            abs.absinfo.value = 0;
            ok = ok && ioctl(fd_, UI_SET_ABSBIT, axis) == 0 && ioctl(fd_, UI_ABS_SETUP, &abs) == 0;
        }
        uinput_setup setup = {};
        // An SPI bus keeps Android treating it as the built-in screen's pen, like the IC's.
        setup.id = id;
        setup.id.bustype = BUS_SPI;
        strncpy(setup.name, kVirtualPenName, UINPUT_MAX_NAME_SIZE - 1);
        ok = ok && ioctl(fd_, UI_DEV_SETUP, &setup) == 0 && ioctl(fd_, UI_DEV_CREATE) == 0;
        if (!ok) {
            PLOG(ERROR) << "uinput setup";
            fd_.reset();
        }
        return ok;
    }

    void report(const PenState& s) {
        bool ink = s.inRange && s.distance == 0;
        unsigned pressure = ink ? s.pressure : 0;
        bool touch = pressure > 0;
        events_.clear();
        add(EV_ABS, ABS_X, s.x);
        add(EV_ABS, ABS_Y, s.y);
        add(EV_ABS, ABS_TILT_X, s.tiltX);
        add(EV_ABS, ABS_TILT_Y, s.tiltY);
        add(EV_ABS, ABS_PRESSURE, static_cast<int>(pressure));
        add(EV_ABS, ABS_DISTANCE, touch ? 0 : 1);
        add(EV_KEY, BTN_TOOL_PEN, s.inRange);
        add(EV_KEY, BTN_TOUCH, touch);
        add(EV_KEY, BTN_STYLUS, s.button1);
        add(EV_KEY, BTN_STYLUS2, s.button2);
        add(EV_SYN, SYN_REPORT, 0);
        // The input core drops repeated values, so sending the whole state is cheap.
        if (write(fd_, events_.data(), events_.size() * sizeof(input_event)) < 0) {
            PLOG(ERROR) << "write " << kUinputDev;
        }
    }

    bool valid() const { return fd_ >= 0; }

  private:
    void add(uint16_t type, uint16_t code, int value) {
        input_event ev = {};
        ev.type = type;
        ev.code = code;
        ev.value = value;
        events_.push_back(ev);
    }

    unique_fd fd_;
    std::vector<input_event> events_;
};

// Applies one IC event; returns true at the end of a frame.
bool applyIcEvent(const input_event& ev, PenState& s) {
    if (ev.type == EV_ABS) {
        switch (ev.code) {
            case ABS_X: s.x = ev.value; break;
            case ABS_Y: s.y = ev.value; break;
            case ABS_TILT_X: s.tiltX = ev.value; break;
            case ABS_TILT_Y: s.tiltY = ev.value; break;
            case ABS_DISTANCE: s.distance = ev.value; break;
            default: break;  // ABS_PRESSURE: the driver's, which we replace
        }
    } else if (ev.type == EV_KEY) {
        switch (ev.code) {
            case BTN_TOOL_PEN: s.inRange = ev.value; break;
            case BTN_STYLUS: s.button1 = ev.value; break;
            case BTN_STYLUS2: s.button2 = ev.value; break;
            default: break;  // BTN_TOUCH: decided here
        }
    } else if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
        return true;
    }
    return false;
}

// After SYN_DROPPED: read the IC's current state instead of the lost events.
void resync(int icFd, PenState& s) {
    input_absinfo abs = {};
    if (ioctl(icFd, EVIOCGABS(ABS_X), &abs) == 0) s.x = abs.value;
    if (ioctl(icFd, EVIOCGABS(ABS_Y), &abs) == 0) s.y = abs.value;
    if (ioctl(icFd, EVIOCGABS(ABS_TILT_X), &abs) == 0) s.tiltX = abs.value;
    if (ioctl(icFd, EVIOCGABS(ABS_TILT_Y), &abs) == 0) s.tiltY = abs.value;
    if (ioctl(icFd, EVIOCGABS(ABS_DISTANCE), &abs) == 0) s.distance = abs.value;
    uint8_t keys[KEY_MAX / 8 + 1] = {};
    if (ioctl(icFd, EVIOCGKEY(sizeof(keys)), keys) >= 0) {
        auto pressed = [&](int code) { return (keys[code / 8] >> (code % 8)) & 1; };
        s.inRange = pressed(BTN_TOOL_PEN);
        s.button1 = pressed(BTN_STYLUS);
        s.button2 = pressed(BTN_STYLUS2);
    }
}

}  // namespace

int main(int argc, char** argv) {
    android::base::InitLogging(argv, android::base::KernelLogger);
    unsigned deadZone = argc > 1 ? static_cast<unsigned>(atoi(argv[1])) : kDefaultDeadZone;
    LOG(INFO) << "pen pressure: dead zone " << deadZone;

    unique_fd mipp(open(kMippDev, O_WRONLY | O_CLOEXEC));
    if (mipp < 0) {
        PLOG(ERROR) << "open " << kMippDev;
        return 1;
    }

    // Without the IC's device or uinput we still relay pressure, as before.
    VirtualPen virtualPen;
    unique_fd ic = openIcPen();
    if (ic >= 0 && virtualPen.create(ic)) {
        if (ioctl(ic, EVIOCGRAB, 1) < 0) {
            PLOG(ERROR) << "grab " << kIcPenName;
            ic.reset();
        }
    } else {
        ic.reset();
    }
    if (ic < 0) {
        LOG(WARNING) << "pen contact: left to the touch driver";
    }

    PenState state;
    if (ic >= 0) {
        resync(ic, state);
    }

    // Started while the pen is connected (or forced on); the hidraw node can show up a moment
    // after the input devices, and disappears when the pen disconnects.
    unique_fd pen;
    bool logged = false;
    for (;;) {
        if (pen < 0) {
            pen = openPenHidraw();
            logged = false;
        }
        pollfd fds[2] = {{ic.get(), POLLIN, 0}, {pen.get(), POLLIN, 0}};
        int ret = poll(fds, 2, pen < 0 ? kRescanMs : -1);
        if (ret < 0) {
            if (errno == EINTR) {
                continue;
            }
            PLOG(ERROR) << "poll";
            return 1;
        }

        if (fds[0].revents & POLLIN) {
            input_event events[64];
            ssize_t len = read(ic, events, sizeof(events));
            for (ssize_t i = 0; i < len / static_cast<ssize_t>(sizeof(input_event)); i++) {
                if (events[i].type == EV_SYN && events[i].code == SYN_DROPPED) {
                    resync(ic, state);
                } else if (applyIcEvent(events[i], state)) {
                    virtualPen.report(state);
                }
            }
        }

        if (fds[1].revents & (POLLIN | POLLERR | POLLHUP)) {
            uint8_t report[64];
            ssize_t len = read(pen, report, sizeof(report));
            if (len < 0 && errno == EINTR) {
                continue;
            }
            if (len <= 0) {
                LOG(INFO) << "pen pressure: hidraw closed";
                pen.reset();
                state.pressure = 0;
                if (virtualPen.valid() && ic >= 0) {
                    virtualPen.report(state);
                }
                continue;
            }
            if (report[0] != kPressureReportId || static_cast<size_t>(len) < 3) {
                continue;
            }
            if (!logged) {
                LOG(INFO) << "pen pressure: first report, " << len << " bytes";
                logged = true;
            }
            unsigned pressure = report[1] | (report[2] << 8);
            pressure = pressure > deadZone ? pressure - deadZone : 0;
            report[1] = pressure & 0xff;
            report[2] = pressure >> 8;
            size_t out = static_cast<size_t>(len) < kMippDataLength ? len : kMippDataLength;
            if (write(mipp, report, out) < 0) {
                PLOG(ERROR) << "write " << kMippDev;
            }
            // Pressure arrives between the IC's frames; report it while the pen is near.
            if (state.pressure != pressure) {
                state.pressure = pressure;
                if (ic >= 0 && state.inRange) {
                    virtualPen.report(state);
                }
            }
        }
    }
}
