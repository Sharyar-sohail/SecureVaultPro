import os
import requests
import json

def get_best_server():
    resp = requests.get('https://api.gofile.io/servers', headers={'User-Agent': 'Mozilla/5.0'})
    data = resp.json()
    if data.get('status') == 'ok':
        servers = data['data']['servers']
        if servers:
            return servers[0]['name']
    return 'store1'

def upload_to_gofile(file_path):
    server = get_best_server()
    print(f"Uploading {file_path} to {server}...")
    url = f"https://{server}.gofile.io/contents/uploadfile"
    with open(file_path, 'rb') as f:
        files = {'file': (os.path.basename(file_path), f, 'application/vnd.android.package-archive')}
        resp = requests.post(url, files=files, headers={'User-Agent': 'Mozilla/5.0'})
    
    res = resp.json()
    print("Response:", res)
    if res.get('status') == 'ok':
        download_page = res['data']['downloadPage']
        return download_page
    else:
        raise RuntimeError(f"Upload failed: {res}")

if __name__ == '__main__':
    classic_path = "SECURE_VAULT_PRO_FINAL_DELIVERY/SecureVaultPro-Classic-v1.0-Fixed.apk"
    nextgen_path = "SECURE_VAULT_PRO_FINAL_DELIVERY/SecureVaultPro-NextGen-v2.0-Flagship.apk"
    
    print("--- Uploading Classic APK ---")
    classic_link = upload_to_gofile(classic_path)
    print("Classic Link:", classic_link)
    
    print("\n--- Uploading NextGen Flagship APK ---")
    nextgen_link = upload_to_gofile(nextgen_path)
    print("NextGen Link:", nextgen_link)
    
    with open("SECURE_VAULT_PRO_FINAL_DELIVERY/DOWNLOAD_LINKS.txt", "w") as out:
        out.write(f"Secure Vault Pro - Classic v1.0 (Fixed): {classic_link}\n")
        out.write(f"Secure Vault Pro - NextGen v2.0 (Flagship): {nextgen_link}\n")
