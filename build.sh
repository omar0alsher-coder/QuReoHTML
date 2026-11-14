#!/data/data/com.termux/files/usr/bin/bash
cd ~/QuReoHTML
rm -rf ~/.android_build_tools
mkdir -p ~/.android_build_tools
cd ~/.android_build_tools

echo "⏳ تحميل أدوات أندرويد..."
wget -q -U "Mozilla/5.0" "https://dl.google.com/android/repository/platform-34_r03.zip" -O p.zip
unzip -q p.zip && mv android-34/android.jar . && rm -rf p.zip android-34

wget -q -U "Mozilla/5.0" "https://dl.google.com/android/repository/build-tools_r34-linux.zip" -O b.zip
unzip -q b.zip && mv android-* build-tools && rm -rf b.zip
chmod -R +x build-tools/

cd ~/QuReoHTML
BT="$HOME/.android_build_tools/build-tools"
A_JAR="$HOME/.android_build_tools/android.jar"

echo "🔨 جاري بناء التطبيق..."
rm -rf obj bin && mkdir -p obj bin
javac -source 1.8 -target 1.8 -cp "$A_JAR" -d obj android/app/src/main/java/com/qureo/premium/MainActivity.java
"$BT/d8" --output . obj/com/qureo/premium/*.class --lib "$A_JAR"
"$BT/aapt" package -f -M android/app/src/main/AndroidManifest.xml -A android/app/src/main/assets -I "$A_JAR" -F bin/app-unaligned.apk
"$BT/aapt" add bin/app-unaligned.apk classes.dex
"$BT/zipalign" -f 4 bin/app-unaligned.apk bin/app-aligned.apk

if [ ! -f debug.keystore ]; then
    keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keydname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out bin/QuReoPremium.apk bin/app-aligned.apk
cp bin/QuReoPremium.apk /sdcard/Download/
echo "✅✅✅ تم البناء بنجاح! التطبيق في مجلد Download"
