#!/usr/bin/env python3
"""Publish one newer, independently signed Family Touch Embedded APK.
Does not publish Admin and never changes APK install/data on devices.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

def output(args):
    return subprocess.check_output(args,text=True,errors="replace")

def manifest_for(source, aapt):
    text=output([str(aapt),"dump","badging",str(source)])
    m=re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",text,re.M)
    if not m:
        raise ValueError("Invalid APK badging")
    return m.group(1),int(m.group(2)),m.group(3)

def cert_hash(apk, apksigner):
    info=output([str(apksigner),"verify","--print-certs",str(apk)])
    m=re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})",info)
    if not m:
        raise ValueError("Missing signing certificate")
    return m.group(1).lower()

def publish(source,root,sdk,dry_run):
    aapt=sdk/"build-tools"/"36.0.0"/"aapt.exe"
    apksigner=sdk/"build-tools"/"36.0.0"/"apksigner.bat"
    live_apk=root/"embedded.apk"
    live_manifest=root/"embedded.json"
    if not all(p.is_file() for p in [source,live_apk,live_manifest,aapt,apksigner]):
        raise FileNotFoundError("Missing release input or signing tools")
    name,code,version=manifest_for(source,aapt)
    if name!="win.fantest.familytouch.embedded":
        raise ValueError("Not an Embedded APK")
    existing=json.loads(live_manifest.read_text(encoding="utf-8"))
    if existing.get("packageName")!=name or code<=existing.get("versionCode",0):
        raise ValueError("Release must be newer than the current feed")
    if not (100_000_000 < source.stat().st_size < 250_000_000):
        raise ValueError("Unexpected APK size")
    if cert_hash(source,apksigner)!=cert_hash(live_apk,apksigner):
        raise ValueError("Signing certificate differs from installed release")
    digest=hashlib.sha256()
    with source.open("rb") as f:
        while block:=f.read(1024*1024):digest.update(block)
    payload={"packageName":name,"versionCode":code,"versionName":version,
        "bytes":source.stat().st_size,"sha256":digest.hexdigest(),
        "apkUrl":"https://family-updates.fantest.win/apks/embedded.apk"}
    print(json.dumps(payload,sort_keys=True))
    if dry_run:
        print("DRY_RUN_VERIFIED")
        return
    stamp=dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup=root/"releases"/stamp
    backup.mkdir(parents=True,exist_ok=False)
    shutil.copy2(live_apk,backup/"embedded.apk")
    shutil.copy2(live_manifest,backup/"embedded.json")
    staged_apk=root/("embedded.apk."+str(code)+".part")
    staged_manifest=root/("embedded.json."+str(code)+".part")
    try:
        shutil.copyfile(source,staged_apk)
        if staged_apk.stat().st_size!=payload["bytes"]:
            raise ValueError("Staged size mismatch")
        staged_manifest.write_text(json.dumps(payload,indent=2,sort_keys=True),encoding="utf-8")
        # App validates both SHA-256 and matching signing certificate.
        os.replace(staged_apk,live_apk)
        os.replace(staged_manifest,live_manifest)
    finally:
        staged_apk.unlink(missing_ok=True)
        staged_manifest.unlink(missing_ok=True)
    print("PUBLISHED_EMBEDDED_ONLY",str(backup))

if __name__=="__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--source",type=Path,required=True)
    parser.add_argument("--root",type=Path,default=Path(r"C:\ProgramData\FamilyTouchUpdates"))
    parser.add_argument("--sdk",type=Path,default=Path(r"C:\Users\Administrator\AppData\Local\Android\Sdk"))
    parser.add_argument("--dry-run",action="store_true")
    opts=parser.parse_args()
    publish(opts.source,opts.root,opts.sdk,opts.dry_run)
