import os
from PIL import Image

def create_perfect_launcher_icon():
    logo_path = r"c:\AndroLLM\core\ui\src\main\res\drawable-nodpi\logo.png"
    character = Image.open(logo_path).convert("RGBA")
    
    # Trim transparent padding around character
    bbox = character.getbbox()
    if bbox:
        character = character.crop(bbox)
        
    canvas_size = 512
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    
    # Scale character to 82% of canvas size for launcher foreground
    target_size = int(canvas_size * 0.82)
    cw, ch = character.size
    aspect = cw / float(ch)
    if cw > ch:
        nw = target_size
        nh = int(target_size / aspect)
    else:
        nh = target_size
        nw = int(target_size * aspect)
        
    character_resized = character.resize((nw, nh), Image.Resampling.LANCZOS)
    
    ox = (canvas_size - nw) // 2
    oy = (canvas_size - nh) // 2
    canvas.paste(character_resized, (ox, oy), character_resized)
    
    launcher_path = r"c:\AndroLLM\app\src\main\res\drawable\ic_launcher_image.png"
    canvas.save(launcher_path, "PNG")
    print(f"Saved un-padded full-bleed launcher image PNG to {launcher_path}")
    
    # Also generate mipmaps
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
        
        icon = Image.new("RGBA", (size, size), bg_color)
        char_target_size = int(size * 0.78)
        if cw > ch:
            mnw = char_target_size
            mnh = int(char_target_size / aspect)
        else:
            mnh = char_target_size
            mnw = int(char_target_size * aspect)
            
        mchar_resized = character.resize((mnw, mnh), Image.Resampling.LANCZOS)
        mox = (size - mnw) // 2
        moy = (size - mnh) // 2
        icon.paste(mchar_resized, (mox, moy), mchar_resized)
        
        icon.save(os.path.join(target_dir, "ic_launcher.png"), "PNG")
        icon.save(os.path.join(target_dir, "ic_launcher_round.png"), "PNG")

if __name__ == "__main__":
    create_perfect_launcher_icon()
