#!/usr/bin/env python3
"""Sign built APKs using an external keystore and create the watch release ZIP."""
import argparse,hashlib,os,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--key-dir',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--build-tools',type=Path,required=True);p.add_argument('--version',default='1.0.0');a=p.parse_args()
root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
for abi in ('armeabi-v7a','arm64-v8a'):
 src=root/'app/build/outputs/apk/release'/f'app-{abi}-release-unsigned.apk'
 dst=a.output/f'Go-web-{a.version}-{abi}.apk'
 aligned=a.output/f'.{abi}-aligned.apk'
 subprocess.run([str(a.build_tools/'zipalign'),'-P','16','-f','4',str(src),str(aligned)],check=True)
 subprocess.run([str(a.build_tools/'apksigner'),'sign','--ks',str(a.key_dir/'goapp-release.jks'),'--ks-key-alias','goapp','--ks-pass','file:'+str(a.key_dir/'release-password.txt'),'--out',str(dst),str(aligned)],check=True)
 subprocess.run([str(a.build_tools/'apksigner'),'verify','--verbose',str(dst)],check=True)
 aligned.unlink()
watch=a.output/f'Go-web-{a.version}-armeabi-v7a.apk'
sums=a.output/'SHA256SUMS.txt';sums.write_text(hashlib.sha256(watch.read_bytes()).hexdigest()+'  '+watch.name+'\n')
with zipfile.ZipFile(a.output/f'Go-web-{a.version}.zip','w',zipfile.ZIP_DEFLATED) as z:
 z.write(watch,watch.name);z.write(sums,sums.name)
print('Release APKs signed and watch archive created.')
