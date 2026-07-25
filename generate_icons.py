"""
Generate Android launcher icon PNGs from the SVG logo design.
Draws the logo programmatically using Pillow at all required mipmap densities.
"""
import math
from PIL import Image, ImageDraw

def draw_logo(size):
    """Draw the QuietHours logo at the given pixel size."""
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    cx, cy = size / 2, size / 2
    # Scale factor relative to the SVG viewBox (logo is centered at 340,170 in a 680x360 viewBox)
    # We want the logo circle (r=130) to fill most of the icon
    scale = size / (130 * 2 * 1.15)  # some padding

    def s(val):
        """Scale a value from SVG coords."""
        return val * scale
    
    def tx(x):
        """Transform SVG x coordinate to image coordinate."""
        return cx + s(x - 340)
    
    def ty(y):
        """Transform SVG y coordinate to image coordinate."""
        return cy + s(y - 170)

    # Outer circle (dark blue border ring)
    r_outer = s(130)
    draw.ellipse(
        [cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer],
        fill=(43, 47, 119)  # #2b2f77
    )

    # Inner circle (medium blue)
    r_inner = s(120)
    draw.ellipse(
        [cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner],
        fill=(61, 67, 160)  # #3d43a0
    )

    # Bell body - approximate the bell shape with polygon
    # SVG path: M -38 30 C -38 8, -30 -10, -18 -22 C -18 -34, -9 -44, 0 -44
    #           C 9 -44, 18 -34, 18 -22 C 30 -10, 38 8, 38 30 Z
    # translated relative to (340, 155)
    bell_cx, bell_cy = 340, 155
    bell_color = (244, 245, 251)  # #f4f5fb
    
    # Create bell shape as a filled polygon (approximated with many points)
    bell_points = []
    # Left side curve: from (-38,30) curving up to (-18,-22)
    steps = 20
    for i in range(steps + 1):
        t = i / steps
        # Quadratic bezier: (-38,30) -> (-38,8) control -> (-30,-10) -> (-18,-22)
        # Approximate as cubic bezier
        x = (1-t)**3 * (-38) + 3*(1-t)**2*t*(-38) + 3*(1-t)*t**2*(-30) + t**3*(-18)
        y = (1-t)**3 * 30 + 3*(1-t)**2*t*8 + 3*(1-t)*t**2*(-10) + t**3*(-22)
        bell_points.append((tx(bell_cx + x), ty(bell_cy + y)))
    
    # Top curve: from (-18,-22) through (0,-44) to (18,-22)
    for i in range(steps + 1):
        t = i / steps
        x = (1-t)**3 * (-18) + 3*(1-t)**2*t*(-18) + 3*(1-t)*t**2*(-9) + t**3*(0)
        y = (1-t)**3 * (-22) + 3*(1-t)**2*t*(-34) + 3*(1-t)*t**2*(-44) + t**3*(-44)
        bell_points.append((tx(bell_cx + x), ty(bell_cy + y)))
    
    for i in range(steps + 1):
        t = i / steps
        x = (1-t)**3 * (0) + 3*(1-t)**2*t*(9) + 3*(1-t)*t**2*(18) + t**3*(18)
        y = (1-t)**3 * (-44) + 3*(1-t)**2*t*(-44) + 3*(1-t)*t**2*(-34) + t**3*(-22)
        bell_points.append((tx(bell_cx + x), ty(bell_cy + y)))
    
    # Right side curve: from (18,-22) down to (38,30)
    for i in range(steps + 1):
        t = i / steps
        x = (1-t)**3 * (18) + 3*(1-t)**2*t*(30) + 3*(1-t)*t**2*(38) + t**3*(38)
        y = (1-t)**3 * (-22) + 3*(1-t)**2*t*(-10) + 3*(1-t)*t**2*(8) + t**3*(30)
        bell_points.append((tx(bell_cx + x), ty(bell_cy + y)))
    
    draw.polygon(bell_points, fill=bell_color)
    
    # Bell base (rect with rounded corners) - approximate as rectangle
    rect_x1 = tx(bell_cx - 46)
    rect_y1 = ty(bell_cy + 30)
    rect_x2 = tx(bell_cx + 46)
    rect_y2 = ty(bell_cy + 40)
    r_rect = s(5)
    draw.rounded_rectangle([rect_x1, rect_y1, rect_x2, rect_y2], radius=r_rect, fill=bell_color)
    
    # Bell clapper (small circle at bottom)
    clapper_cx = tx(bell_cx)
    clapper_cy = ty(bell_cy + 52)
    clapper_r = s(9)
    draw.ellipse(
        [clapper_cx - clapper_r, clapper_cy - clapper_r,
         clapper_cx + clapper_r, clapper_cy + clapper_r],
        fill=bell_color
    )

    # Small clock circle (orange)
    clock_cx_svg, clock_cy_svg = 374, 200
    clock_r = s(34)
    ccx = tx(clock_cx_svg)
    ccy = ty(clock_cy_svg)
    
    # Orange fill
    draw.ellipse(
        [ccx - clock_r, ccy - clock_r, ccx + clock_r, ccy + clock_r],
        fill=(255, 182, 72),  # #ffb648
        outline=(232, 154, 31),  # #e89a1f
        width=max(1, int(s(2)))
    )
    
    # Clock hands (dark blue)
    hand_width = max(1, int(s(3)))
    # Hour hand (vertical, pointing up)
    draw.line(
        [(ccx, ccy), (ccx, ty(180))],
        fill=(43, 47, 119),
        width=hand_width
    )
    # Minute hand (horizontal, pointing right)
    draw.line(
        [(ccx, ccy), (tx(389), ccy)],
        fill=(43, 47, 119),
        width=hand_width
    )
    # Center dot
    dot_r = s(2.5)
    draw.ellipse(
        [ccx - dot_r, ccy - dot_r, ccx + dot_r, ccy + dot_r],
        fill=(43, 47, 119)
    )

    # Slash line (red with white center)
    slash_width_outer = max(2, int(s(10)))
    slash_width_inner = max(1, int(s(3)))
    
    slash_x1, slash_y1 = tx(270), ty(230)
    slash_x2, slash_y2 = tx(410), ty(110)
    
    draw.line(
        [(slash_x1, slash_y1), (slash_x2, slash_y2)],
        fill=(255, 107, 107),  # #ff6b6b
        width=slash_width_outer
    )
    draw.line(
        [(slash_x1, slash_y1), (slash_x2, slash_y2)],
        fill=(244, 245, 251),  # #f4f5fb
        width=slash_width_inner
    )

    # Outer circle border
    border_width = max(1, int(s(3)))
    draw.ellipse(
        [cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner],
        outline=(27, 30, 82),  # #1b1e52
        width=border_width
    )

    return img


# Android mipmap density buckets
densities = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

import os

base_path = os.path.join('app', 'src', 'main', 'res')

for folder, size in densities.items():
    dir_path = os.path.join(base_path, folder)
    os.makedirs(dir_path, exist_ok=True)
    
    icon = draw_logo(size)
    
    # Save as ic_launcher.png
    icon.save(os.path.join(dir_path, 'ic_launcher.png'), 'PNG')
    
    # Save as ic_launcher_round.png (same image, already circular)
    icon.save(os.path.join(dir_path, 'ic_launcher_round.png'), 'PNG')
    
    print(f"Generated {folder}: {size}x{size}")

print("\nDone! All launcher icons generated.")
