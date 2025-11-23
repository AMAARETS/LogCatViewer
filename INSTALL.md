# הוראות התקנה - Logcat Viewer

## דרישות מקדימות

### 1. התקנת Java Development Kit (JDK)
התוכנה דורשת JDK 8 או גבוה יותר.

**Windows:**
- הורד מ: https://adoptium.net/
- התקן והוסף ל-PATH

**macOS:**
```bash
brew install openjdk
```

**Linux:**
```bash
sudo apt install openjdk-11-jdk  # Ubuntu/Debian
sudo dnf install java-11-openjdk # Fedora
```

### 2. Android Platform Tools (אופציונלי)

**התוכנה תוריד ותתקין ADB אוטומטית!**

אם כבר יש לך Android SDK מותקן, התוכנה תזהה אותו אוטומטית במיקומים הבאים:
- Windows: `%LOCALAPPDATA%\Android\Sdk\platform-tools`
- macOS: `~/Library/Android/sdk/platform-tools`
- Linux: `~/Android/Sdk/platform-tools`

אם ADB לא נמצא, התוכנה תוריד אותו אוטומטית ל: `~/.logcat-viewer/platform-tools/`

### 3. הפעלת USB Debugging במכשיר Android
1. עבור ל: הגדרות → אודות הטלפון
2. לחץ 7 פעמים על "מספר גרסה" להפעלת מצב מפתח
3. חזור להגדרות → אפשרויות מפתח
4. הפעל "USB Debugging"

## הרצת התוכנה

### הרצה במצב פיתוח

**Windows:**
```cmd
gradlew.bat run
```

**macOS/Linux:**
```bash
./gradlew run
```

### בניית קובץ הפצה

**Windows (MSI):**
```cmd
gradlew.bat packageMsi
```
הקובץ יווצר ב: `build\compose\binaries\main\msi\`

**macOS (DMG):**
```bash
./gradlew packageDmg
```
הקובץ יווצר ב: `build/compose/binaries/main/dmg/`

**Linux (DEB):**
```bash
./gradlew packageDeb
```
הקובץ יווצר ב: `build/compose/binaries/main/deb/`

## פתרון בעיות

### "מוריד Android Platform Tools..."
זה תקין! בהרצה הראשונה התוכנה מורידה את ADB (כ-10MB). זה קורה פעם אחת בלבד.

### "Cannot reach ADB server"
התוכנה תנסה להריץ את ADB server אוטומטית. אם זה לא עובד:
1. סגור את התוכנה
2. מחק את התיקייה `~/.logcat-viewer/`
3. הרץ את התוכנה שוב

### "No devices found"
**פתרון:**
1. ודא שהמכשיר מחובר בכבל USB
2. ודא ש-USB Debugging מופעל במכשיר
3. אשר את ההודעה "Allow USB debugging" במכשיר
4. נסה כבל USB אחר
5. הרץ:
   ```bash
   adb kill-server
   adb start-server
   ```

### "Device unauthorized"
**פתרון:**
1. נתק את המכשיר
2. הרץ: `adb kill-server`
3. חבר מחדש את המכשיר
4. אשר את ההודעה במכשיר

### בעיות קומפילציה
**פתרון:**
1. נקה את הפרויקט:
   ```bash
   ./gradlew clean
   ```
2. בנה מחדש:
   ```bash
   ./gradlew build
   ```

## שימוש בתוכנה

1. **בחירת מכשיר**: לחץ על התפריט הנפתח ובחר מכשיר
2. **התחלת לוגים**: לחץ על כפתור ההפעלה (▶️)
3. **סינון לוגים**:
   - חיפוש טקסט: הקלד בשדה החיפוש
   - סינון לפי רמה: לחץ על כפתורי V/D/I/W/E/A
   - סינון לפי תג: הקלד בשדה "סינון לפי תג"
4. **ייצוא לוגים**: לחץ על כפתור השמירה (💾)
5. **ניקוי לוגים**: לחץ על כפתור הפח
6. **עצירת לוגים**: לחץ על כפתור העצירה (⏹)

## רישיון

MIT License - ראה קובץ LICENSE לפרטים
