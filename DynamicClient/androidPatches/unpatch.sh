#!/bin/sh

if [ -z "$1" ]; then
    echo "usage: ./patch.sh <path to AOSP>"
    exit 1
fi

echo "Performing dry-run first..."

patch --dry-run -R -p 1 -d $1/frameworks/base < $(dirname $0)/android-4.3_r1_frameworks_base.diff

patch --dry-run -R -p 1 -d $1/frameworks/opt/telephony < $(dirname $0)/android-4.3_r1_frameworks_opt_telephony.diff

echo "Dry run completed"

while true; do
    read -p "Do you want to undo the patches? (y/n) " yn
    case $yn in
        [Yy]* ) break;;
        [Nn]* ) exit;;
    esac
done

patch -R -p 1 -d $1/frameworks/base < $(dirname $0)/android-4.3_r1_frameworks_base.diff
echo ">> patched frameworks/base\n"

patch -R -p 1 -d $1/frameworks/opt/telephony < $(dirname $0)/android-4.3_r1_frameworks_opt_telephony.diff
echo ">> patched frameworks/opt/telephony\n"

