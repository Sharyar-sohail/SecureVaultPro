import os
import requests
import json
import time

def get_servers():
    try:
        resp = requests.get('https://api.gofile.io/servers', headers={'User-Agent': 'Mozilla/5.0'}, timeout=10)
        data = resp.json()
        if data.get('status') == 'ok':
            return [s['name'] for s in data['data']['servers']]
    except Exception as e:
        print(f"Error fetching servers: {e}")
    return ['store8', 'store3', 'store1', 'store4']

def upload_to_gofile(file_path):
    servers = get_servers()
    for server in servers[:5]:
        print(f"Attempting upload of {file_path} to {server}...")
        url = f"https://{server}.gofile.io/contents/uploadfile"
        try:
            with open(file_path, 'rb') as f:
                files = {'file': (os.path.basename(file_path), f, 'application/vnd.android.package-archive')}
                resp = requests.post(url, files=files, headers={'User-Agent': 'Mozilla/5.0'}, timeout=120)
            
            res = resp.json()
            print("Response:", res)
            if res.get('status') == 'ok':
                download_page = res['data']['downloadPage']
                return download_page
            else:
                print(f"Server {server} returned non-ok: {res}")
        except Exception as e:
            print(f"Upload to {server} failed: {e}. Retrying with next server...")
            time.sleep(2)
            
    raise RuntimeError(f"All servers failed for {file_path}")

if __name__ == '__main__':
    classic_path = "SECURE_VAULT_PRO_FINAL_DELIVERY/SecureVaultPro-Classic-v2.0.apk"
    nextgen_path = "SECURE_VAULT_PRO_FINAL_DELIVERY/SecureVaultPro-NextGen-v2.0.apk"
    
    print("--- Uploading Classic APK ---")
    classic_link = upload_to_gofile(classic_path)
    print("Classic Link:", classic_link)
    
    print("\n--- Uploading NextGen Flagship APK ---")
    nextgen_link = upload_to_gofile(nextgen_path)
    print("NextGen Link:", nextgen_link)
    
    with open("SECURE_VAULT_PRO_FINAL_DELIVERY/DOWNLOAD_LINKS.txt", "w") as out:
        out.write(f"Secure Vault Pro - Classic v1.0 (Fixed): {classic_link}\n")
        out.write(f"Secure Vault Pro - NextGen v2.0 (Flagship): {nextgen_link}\n")
