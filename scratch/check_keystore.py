import subprocess

keytool = r"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
keystore = r"C:\Users\Abrar Safin\Downloads\main"

passwords = [
    "123456", "android", "main", "password", "keypass", "storepass",
    "androllm", "Abrar", "Safin", "abrar", "safin", "12345678", "123456789",
    "andro", "AndroLLM", "Main", "key", "keystore", "admin", "000000"
]

for p in passwords:
    cmd = [keytool, "-list", "-v", "-keystore", keystore, "-storepass", p]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if "incorrect" not in res.stderr and "incorrect" not in res.stdout:
        print(f"FOUND PASSWORD: '{p}'")
        print("OUTPUT:\n", res.stdout)
        break
else:
    print("No matching password found in default dictionary.")
