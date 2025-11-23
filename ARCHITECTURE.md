# ארכיטקטורת התוכנה

## סקירה כללית

Logcat Viewer היא תוכנת דסקטופ חוצת פלטפורמות לצפייה בלוגים של מכשירי אנדרואיד.

## מבנה הפרויקט

```
src/main/kotlin/
├── Main.kt              - נקודת הכניסה והממשק הראשי
├── Components.kt        - רכיבי UI (סינונים, תצוגת לוגים, וכו')
├── LogcatViewModel.kt   - לוגיקה עסקית וניהול מצב
└── EmbeddedAdb.kt       - ניהול ADB מוטמע (הורדה, התקנה, הפעלה)
```

## רכיבים עיקריים

### 1. Main.kt
- **תפקיד**: ממשק המשתמש הראשי
- **טכנולוגיה**: Compose Desktop
- **תכונות**:
  - Toolbar עם בחירת מכשיר וכפתורי בקרה
  - FilterBar לסינון לוגים
  - LogDisplay לתצוגת הלוגים
  - StatusBar להצגת מצב

### 2. LogcatViewModel.kt
- **תפקיד**: ניהול מצב ולוגיקה עסקית
- **אחריות**:
  - אתחול ADB והרצת server
  - זיהוי מכשירים מחוברים
  - קבלת לוגים מהמכשיר
  - סינון וחיפוש בלוגים
  - ייצוא לוגים לקובץ

**תהליך אתחול:**
```kotlin
1. קריאה ל-EmbeddedAdb.startAdbServer():
   - בדיקה אם ADB מותקן ב-~/.logcat-viewer/
   - אם לא - הורדה אוטומטית מ-Google
   - הפעלת "adb start-server"
   - המתנה לאתחול מלא
   
2. קבלת גרסת ADB (לוג):
   - EmbeddedAdb.getAdbVersion()
   
3. חיבור ל-ADB server:
   - יצירת JadbConnection() (localhost:5037)
   - אם נכשל - הצגת שגיאה
   
4. סריקת מכשירים:
   - startDeviceScanning() - כל 3 שניות
   - refreshDevices() - סריקה ראשונית
```

### 3. EmbeddedAdb.kt
- **תפקיד**: ניהול מלא של ADB מוטמע
- **פונקציות עיקריות**:
  - `ensureAdbInstalled()` - בדיקה והתקנה אם נדרש
  - `startAdbServer()` - הפעלת ADB daemon
  - `isAdbServerRunning()` - בדיקת סטטוס
  - `getAdbVersion()` - קבלת גרסת ADB
  - `stopAdbServer()` - עצירת ADB

**תהליך התקנה:**
1. בדיקה אם ADB קיים ב-`~/.logcat-viewer/platform-tools/`
2. אם לא - זיהוי מערכת הפעלה (Windows/Mac/Linux)
3. הורדת platform-tools המתאים מ-Google
4. חילוץ הקבצים
5. הגדרת הרשאות הרצה (Unix)
6. הפעלת `adb start-server`

**URLs להורדה:**
- Windows: `platform-tools-latest-windows.zip`
- macOS: `platform-tools-latest-darwin.zip`
- Linux: `platform-tools-latest-linux.zip`

**מיקום התקנה:**
- `~/.logcat-viewer/platform-tools/adb[.exe]`

### 4. Components.kt
- **תפקיד**: רכיבי UI לשימוש חוזר
- **רכיבים**:
  - `DeviceSelector` - בחירת מכשיר
  - `FilterBar` - סינונים וחיפוש
  - `FilterChip` - כפתורי סינון לפי רמת לוג
  - `LogDisplay` - תצוגת הלוגים עם גלילה
  - `LogItem` - פריט לוג בודד
  - `StatusBar` - שורת מצב

## זרימת נתונים

```
1. אתחול:
   Main.kt → LogcatViewModel.initialize()
   ↓
   חיפוש/הורדת ADB
   ↓
   אתחול DDMLib
   ↓
   סריקת מכשירים

2. קבלת לוגים:
   User clicks Play → startLogcat()
   ↓
   device.executeShellCommand("logcat -v time")
   ↓
   LogcatReceiver.addOutput()
   ↓
   parseLogLine()
   ↓
   logs.add(entry)
   ↓
   UI מתעדכן אוטומטית (Compose State)

3. סינון:
   User types in search → searchText.value = ...
   ↓
   getFilteredLogs() מחושב מחדש
   ↓
   UI מתעדכן עם רשימה מסוננת
```

## ניהול מצב

התוכנה משתמשת ב-Compose State Management:

```kotlin
// Observable state
val devices = mutableStateListOf<IDevice>()
val logs = mutableStateListOf<LogEntry>()
val searchText = mutableStateOf("")

// כל שינוי ב-state מעדכן את ה-UI אוטומטית
```

## Coroutines וחוטים

- **Main Thread**: UI rendering (Compose)
- **IO Dispatcher**: קריאות רשת, קבצים, ADB
- **Scope**: CoroutineScope עם SupervisorJob למניעת קריסות

```kotlin
scope.launch {
    // Background work
    withContext(Dispatchers.IO) {
        // IO operations
    }
    // Back to main thread for UI updates
}
```

## תקשורת עם ADB

התוכנה משתמשת ב-JADB (Pure Java):

```kotlin
// חיבור ל-ADB server
val jadb = JadbConnection() // localhost:5037

// קבלת מכשירים
val devices = jadb.devices

// קבלת מידע על מכשיר
val stream = device.executeShell("getprop", "ro.product.model")
val model = BufferedReader(InputStreamReader(stream)).readLine()

// הרצת logcat
val stream = device.executeShell("logcat", "-v", "threadtime")
val reader = BufferedReader(InputStreamReader(stream))
// קריאת שורות בזמן אמת
```

### פרוטוקול ADB

JADB מממש את פרוטוקול ADB דרך TCP socket:
1. פתיחת חיבור ל-localhost:5037
2. שליחת פקודות בפורמט: `[length][command]`
3. קבלת תשובות: `OKAY` או `FAIL`
4. עבור shell commands - stream של נתונים

## פורמט לוגים

```
Format: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE
Example: 11-23 14:30:45.123 1234 5678 I MyApp: Hello World

Regex: (\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEA])\s+([^:]+):\s+(.*)
```

## ביצועים

- **מגבלת לוגים**: 10,000 רשומות (FIFO)
- **Virtual Scrolling**: LazyColumn של Compose
- **Filtering**: מחושב מחדש רק כשהמסננים משתנים
- **Memory**: ניקוי אוטומטי של לוגים ישנים

## אבטחה

- הורדת ADB רק מהאתר הרשמי של Google
- בדיקת הרשאות הרצה לפני שימוש
- ללא שמירת מידע רגיש
- ייצוא לוגים רק למיקום שהמשתמש בחר

## תלויות

```gradle
- compose.desktop.currentOs      // UI framework
- compose.material               // Material Design
- jadb:v1.2.1                   // Pure Java ADB client
- kotlinx-coroutines-core       // Async operations
```

## בניית הפצה

```bash
# Windows
gradlew packageMsi

# macOS  
gradlew packageDmg

# Linux
gradlew packageDeb
```

הקבצים נוצרים ב: `build/compose/binaries/main/`
