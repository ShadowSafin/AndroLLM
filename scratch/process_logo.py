import os
from PIL import Image, ImageFilter

def create_transparent_logo():
    img_path = r"C:\Users\Abrar Safin\.gemini\antigravity\brain\d8225b74-9a10-48be-9053-506cedef1d29\.user_uploaded\media_1786147766036.jpg"
    img = Image.open(img_path).convert("RGBA")
    width, height = img.size
    
    # Create new RGBA image
    datas = img.getdata()
    new_data = []
    
    # Center coordinates
    cx, cy = width / 2.0, height / 2.0
    
    for y in range(height):
        for x in range(width):
            r, g, b, a = img.getpixel((x, y))
            
            # Distance from center
            dx = x - cx
            dy = y - cy
            dist = (dx*dx + dy*dy) ** 0.5
            
            # Check for black background or dark starry ring background
            # The character is primarily green (g > r and g > b) and warm gold headphones (r > 150, g > 120, b < 100)
            # Black background: r < 30, g < 30, b < 30
            # Starry gold ring outside character bounds: dist > width * 0.38
            
            is_black = (r < 35 and g < 35 and b < 35)
            is_outer_ring = (dist > width * 0.38)
            
            if is_black or is_outer_ring:
                new_data.append((0, 0, 0, 0)) # Fully transparent
            else:
                # Keep character pixel
                new_data.append((r, g, b, 255))
                
    img.putdata(new_data)
    
    # Save as true transparent PNG
    out_dir = r"c:\AndroLLM\core\ui\src\main\res\drawable-nodpi"
    os.makedirs(out_dir, exist_ok=True)
    png_path = os.path.join(out_dir, "logo.png")
    img.save(png_path, "PNG")
    print(f"Saved true transparent logo PNG to {png_path}")
    
    # Save for launcher icon as well
    launcher_path = r"c:\AndroLLM\app\src\main\res\drawable\ic_launcher_image.png"
    img.save(launcher_path, "PNG")
    print(f"Saved launcher icon PNG to {launcher_path}")

if __name__ == "__main__":
    create_transparent_logo()
