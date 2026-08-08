import os
from PIL import Image

def generate_mipmaps():
    logo_path = r"c:\AndroLLM\core\ui\src\main\res\drawable-nodpi\logo.png"
    character = Image.open(logo_path).convert("RGBA")
    
    # Trim transparent padding around character
    bbox = character.getbbox()
    if bbox:
        character = character.crop(bbox)
        
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    
    res_dir = r"c:\AndroLLM\app\src\main\res"
    bg_color = (245, 244, 237, 255) # Warm parchment #F5F4ED
    
    for folder, size in densities.items():
        target_dir = os.path.join(res_dir, folder)
        os.makedirs(target_dir, exist_ok=True)
        
        # Create square icon with background
        icon = Image.new("RGBA", (size, size), bg_color)
        
        # Scale character to 55% of icon size
        char_target_size = int(size * 0.55)
        cw, ch = character.size
        aspect = cw / float(ch)
        if cw > ch:
            nw = char_target_size
            nh = int(char_target_size / aspect)
        else:
            nh = char_target_size
            nw = int(char_target_size * aspect)
            
        char_resized = character.resize((nw, nh), Image.Resampling.LANCZOS)
        
        ox = (size - nw) // 2
        oy = (size - nh) // 2
        icon.paste(char_resized, (ox, oy), char_resized)
        
        ic_path = os.path.join(target_dir, "ic_launcher.png")
        ic_round_path = os.path.join(target_dir, "ic_launcher_round.png")
        
        icon.save(ic_path, "PNG")
        icon.save(ic_round_path, "PNG")
        print(f"Saved {folder} ({size}x{size})")

if __name__ == "__main__":
    generate_mipmaps()
