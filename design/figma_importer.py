#!/usr/bin/env python3
"""Figma Design Injection & Automation Tool for Linux / Wayland (Hyprland).

Injects native Figma frames into an active Figma web session (Firefox)
by leveraging Figma's native SVG clipboard ingestion engine, automated
Wayland clipboard management (wl-copy), and desktop GUI control (wtype/evdev).

Used by Worker agents to create and position redesigned screen frames
pixel-perfectly on the Figma canvas without modifying reference baselines.
"""

import os
import sys
import time
import re
import subprocess
from evdev import UInput, ecodes as e


def get_monitor_offset() -> tuple[int, int]:
    """Get monitor HDMI-A-2 offset from hyprctl monitors."""
    try:
        out = subprocess.check_output(["hyprctl", "monitors"], text=True)
        m = re.search(r"Monitor HDMI-A-2.*?at (-?\d+)x(-?\d+)", out, re.DOTALL)
        if m:
            return int(m.group(1)), int(m.group(2))
    except Exception:
        pass
    return 0, 0


def focus_figma_window() -> None:
    """Ensure Firefox on Workspace 2 is focused."""
    try:
        subprocess.run(["hyprctl", "eval", "hl.dispatch(hl.dsp.focus({ workspace = 2 }))"])
        time.sleep(0.4)
    except Exception as ex:
        print(f"Warning: could not focus workspace 2: {ex}")


def get_layout_points() -> dict[str, tuple[int, int]]:
    """Calculate UI targets accounting for monitor placement."""
    x_off, y_off = get_monitor_offset()
    return {
        "canvas": (x_off + 880, y_off + 600),
        "sidebar_x": (x_off + 1717, y_off + 390),
        "sidebar_y": (x_off + 1812, y_off + 390),
        "sidebar_name": (x_off + 1722, y_off + 257),
    }


def get_cursor_pos() -> tuple[int, int]:
    """Get current cursor position in global layout coordinates."""
    try:
        out = subprocess.check_output(["hyprctl", "cursorpos"], text=True).strip()
        parts = [int(x.strip()) for x in out.split(",")]
        return parts[0], parts[1]
    except Exception:
        return 0, 0


def move_and_click(x: int, y: int, button: str = "left", delay: float = 0.05) -> None:
    """Move cursor to (x, y) and perform click via evdev UInput."""
    cap = {
        e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL],
        e.EV_KEY: [e.BTN_LEFT, e.BTN_RIGHT, e.BTN_MIDDLE],
    }
    btn_code = e.BTN_RIGHT if button.lower() == "right" else e.BTN_LEFT
    ui = UInput(cap, name="figma-injector-mouse")
    try:
        cur_x, cur_y = get_cursor_pos()
        dx = x - cur_x
        dy = y - cur_y
        steps = max(abs(dx), abs(dy)) // 50 + 1
        step_dx = dx / steps
        step_dy = dy / steps

        for _ in range(steps):
            cur_x, cur_y = get_cursor_pos()
            rem_x = x - cur_x
            rem_y = y - cur_y
            if abs(rem_x) == 0 and abs(rem_y) == 0:
                break
            mx = int(round(rem_x if abs(rem_x) < abs(step_dx) else step_dx))
            my = int(round(rem_y if abs(rem_y) < abs(step_dy) else step_dy))
            ui.write(e.EV_REL, e.REL_X, mx)
            ui.write(e.EV_REL, e.REL_Y, my)
            ui.syn()
            time.sleep(0.01)

        cur_x, cur_y = get_cursor_pos()
        if cur_x != x or cur_y != y:
            ui.write(e.EV_REL, e.REL_X, x - cur_x)
            ui.write(e.EV_REL, e.REL_Y, y - cur_y)
            ui.syn()
            time.sleep(0.02)

        ui.write(e.EV_KEY, btn_code, 1)
        ui.syn()
        time.sleep(delay)
        ui.write(e.EV_KEY, btn_code, 0)
        ui.syn()
        time.sleep(delay)
    finally:
        ui.close()


def copy_svg_to_clipboard(svg_path_or_content: str) -> None:
    """Copy SVG content into Wayland clipboard using wl-copy."""
    if os.path.exists(svg_path_or_content):
        with open(svg_path_or_content, "r", encoding="utf-8") as f:
            content = f.read()
    else:
        content = svg_path_or_content

    # Copy as text/plain so Figma's SVG ingestion parser activates
    p = subprocess.Popen(["wl-copy", "-t", "text/plain"], stdin=subprocess.PIPE)
    p.communicate(input=content.encode("utf-8"))
    if p.returncode != 0:
        raise RuntimeError(f"wl-copy failed with returncode {p.returncode}")


def inject_svg_into_figma(
    svg_path_or_content: str,
    target_x: int = 0,
    target_y: int = 920,
    frame_name: str = "Frame-PixelPlayer-Redesign",
    settle_time: float = 2.0,
) -> None:
    """Complete workflow: Focus -> Staging -> Focus Canvas -> Paste -> Position -> Rename."""
    print(f"[0/5] Activating Firefox Figma workspace...")
    focus_figma_window()
    points = get_layout_points()

    print(f"[1/5] Staging SVG into Wayland clipboard...")
    copy_svg_to_clipboard(svg_path_or_content)
    time.sleep(0.3)

    print(f"[2/5] Focusing Figma canvas in Firefox...")
    # Click empty canvas
    move_and_click(points["canvas"][0], points["canvas"][1])
    time.sleep(0.2)

    # Deselect all layers to prevent nesting inside existing frames
    subprocess.run(["wtype", "-k", "Escape"])
    time.sleep(0.15)
    subprocess.run(["wtype", "-k", "Escape"])
    time.sleep(0.3)

    print(f"[3/5] Sending Paste command (Ctrl+V) to Figma...")
    subprocess.run(["wtype", "-M", "ctrl", "-k", "v", "-m", "ctrl"])
    time.sleep(settle_time)

    print(f"[4/5] Aligning coordinates to X={target_x}, Y={target_y} via sidebar...")
    # Click X coordinate box in right sidebar
    move_and_click(points["sidebar_x"][0], points["sidebar_x"][1])
    time.sleep(0.15)
    # Select all and type X value
    subprocess.run(["wtype", "-M", "ctrl", "-k", "a", "-m", "ctrl"])
    time.sleep(0.08)
    subprocess.run(["wtype", str(target_x)])
    time.sleep(0.08)
    # Tab jumps to Y coordinate box in Figma
    subprocess.run(["wtype", "-k", "Tab"])
    time.sleep(0.08)
    subprocess.run(["wtype", "-M", "ctrl", "-k", "a", "-m", "ctrl"])
    time.sleep(0.08)
    subprocess.run(["wtype", str(target_y)])
    time.sleep(0.08)
    subprocess.run(["wtype", "-k", "Return"])
    time.sleep(0.4)

    print(f"[5/5] Renaming layer to '{frame_name}'...")
    # Trigger rename shortcut (Ctrl+R)
    subprocess.run(["wtype", "-M", "ctrl", "-k", "r", "-m", "ctrl"])
    time.sleep(0.1)
    subprocess.run(["wtype", frame_name])
    time.sleep(0.05)
    subprocess.run(["wtype", "-k", "Return"])
    time.sleep(0.2)

    print(f"Injection complete! Verify node metadata via Figma MCP get_metadata.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: figma_importer.py <path_to_svg> [x] [y] [frame_name]")
        sys.exit(1)

    svg_file = sys.argv[1]
    x_pos = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    y_pos = int(sys.argv[3]) if len(sys.argv) > 3 else 920
    name = sys.argv[4] if len(sys.argv) > 4 else "Frame-PixelPlayer-Redesign"

    inject_svg_into_figma(svg_file, target_x=x_pos, target_y=y_pos, frame_name=name)
