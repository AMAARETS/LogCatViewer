# תיקונים סופיים - בחירה וגרירה

## 🐛 בעיות שזוהו

### 1. גרירה לא עובדת
**הבעיה:** כאשר גוררים את העכבר מעל שורות, הבחירה לא מתעדכנת.

**הסיבה:** 
- ה-state של `isDragging` היה מקומי לכל `LogItem`
- כשעוברים משורה לשורה, כל שורה לא יודעת שאנחנו בתהליך גרירה
- ה-parent לא ידע מתי אנחנו גוררים

### 2. תפריט הקשר כפול
**הבעיה:** לחיצה ימנית על שורות נבחרות מציגה שני תפריטים במקביל.

**הסיבה:**
- `ContextMenuArea` פתח תפריט אחד
- הטיפול הידני ב-right click פתח תפריט שני
- שני המנגנונים פעלו במקביל

## ✅ הפתרונות שיושמו

### פתרון 1: העברת State לרמת Parent

**שינויים ב-`LogDisplay.kt`:**
```kotlin
// State מנוהל ברמת ה-parent
var isDragging by remember { mutableStateOf(false) }
var dragStartIndex by remember { mutableStateOf<Int?>(null) }
var lastHoveredIndex by remember { mutableStateOf<Int?>(null) }
```

**העברת State לילדים:**
```kotlin
LogItemWithSelection(
    log = log,
    isSelected = selectedIndices.contains(index),
    isDragging = isDragging,  // ← מועבר מה-parent
    onHover = { isHovering ->
        if (isHovering && isDragging && dragStartIndex != null) {
            // עדכון הבחירה בזמן אמת
            val start = minOf(dragStartIndex!!, index)
            val end = maxOf(dragStartIndex!!, index)
            selectedIndices = (start..end).toSet()
        }
    },
    ...
)
```

### פתרון 2: הפרדת תפריטי הקשר

**שינויים ב-`LogItem.kt`:**

**לשורות לא נבחרות - `ContextMenuArea`:**
```kotlin
if (!isSelected) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("העתק הודעה") { ... },
                ContextMenuItem("העתק שורה מלאה") { ... }
            )
        }
    ) {
        LogItemRow(...)
    }
}
```

**לשורות נבחרות - טיפול ידני:**
```kotlin
else {
    // ללא ContextMenuArea - רק טיפול ידני
    LogItemRow(
        ...
        onContextMenu = onContextMenu  // ← מפעיל תפריט ב-parent
    )
}
```

**טיפול ב-right click:**
```kotlin
PointerEventType.Press -> {
    if (event.button == PointerButton.Secondary && onContextMenu != null) {
        val position = event.changes.first().position
        val offset = DpOffset(
            (position.x / density).dp,
            (position.y / density).dp
        )
        onContextMenu(offset)  // ← רק תפריט אחד!
    }
}
```

### פתרון 3: תפריט מרובה שורות ב-Parent

**ב-`LogDisplay.kt`:**
```kotlin
DropdownMenu(
    expanded = showMultiSelectMenu,
    onDismissRequest = { showMultiSelectMenu = false },
    offset = multiSelectMenuOffset
) {
    DropdownMenuItem(
        onClick = {
            val selectedLogs = selectedIndices.sorted().mapNotNull { cachedLogs[it] }
            val text = selectedLogs.joinToString("\n") { log ->
                "${log.timestamp} ${log.pid}/${log.tid} ${log.level.displayName}/${log.tag}: ${log.message}"
            }
            copyToClipboard(text)
            selectedIndices = emptySet()
            showMultiSelectMenu = false
        }
    ) {
        Text("העתק ${selectedIndices.size} שורות")
    }
    
    DropdownMenuItem(
        onClick = {
            selectedIndices = emptySet()
            showMultiSelectMenu = false
        }
    ) {
        Text("בטל בחירה")
    }
}
```

## 🎯 איך זה עובד עכשיו

### תרחיש 1: בחירה בגרירה
1. משתמש לוחץ על שורה → `onSelectionStart()` מופעל
2. `isDragging = true` ב-parent
3. `dragStartIndex` נשמר
4. משתמש מזיז עכבר → `onHover(true)` מופעל בכל שורה
5. ה-parent בודק אם `isDragging && dragStartIndex != null`
6. מעדכן את `selectedIndices` לכל הטווח
7. כל השורות בטווח מקבלות `isSelected = true`
8. משתמש משחרר → `isDragging = false`

### תרחיש 2: בחירה עם Ctrl
1. משתמש לוחץ Ctrl+Click → `onSelectionStart(isCtrlPressed=true)`
2. השורה מתווספת/מוסרת מ-`selectedIndices`
3. `isDragging = false` (לא גרירה)

### תרחיש 3: לחיצה ימנית על שורה בודדת
1. `isSelected = false` → משתמש ב-`ContextMenuArea`
2. תפריט עם "העתק הודעה" ו"העתק שורה מלאה"
3. רק תפריט אחד מופיע

### תרחיש 4: לחיצה ימנית על שורות נבחרות
1. `isSelected = true` → ללא `ContextMenuArea`
2. טיפול ידני ב-right click
3. `onContextMenu(offset)` מופעל
4. ה-parent פותח `DropdownMenu` במיקום הנכון
5. רק תפריט אחד מופיע

## 📊 ארכיטקטורת State Management

```
LogDisplay (Parent)
├── State:
│   ├── selectedIndices: Set<Int>
│   ├── isDragging: Boolean
│   ├── dragStartIndex: Int?
│   └── showMultiSelectMenu: Boolean
│
└── LogItemWithSelection (Children)
    ├── Props:
    │   ├── isSelected: Boolean (from parent)
    │   └── isDragging: Boolean (from parent)
    │
    └── Events:
        ├── onSelectionStart(isCtrlPressed)
        ├── onHover(isHovering)
        ├── onDragEnd()
        └── onContextMenu(offset)
```

## 🔑 עקרונות עיצוב

### 1. Single Source of Truth
- כל ה-state של הבחירה והגרירה ב-parent
- הילדים רק מדווחים על events
- הילדים מקבלים props ומגיבים להם

### 2. Separation of Concerns
- `LogDisplay` - ניהול state ולוגיקה
- `LogItem` - תצוגה ו-events
- כל אחד עושה את התפקיד שלו

### 3. Conditional Rendering
- שורות לא נבחרות → `ContextMenuArea`
- שורות נבחרות → טיפול ידני
- אין כפילות, אין קונפליקטים

## ✅ תוצאות

- [x] גרירה עובדת חלק ומהיר
- [x] בחירה מתעדכנת בזמן אמת
- [x] רק תפריט אחד מופיע
- [x] תפריט נשאר פתוח עד שבוחרים אופציה
- [x] העתקה עובדת מושלם
- [x] ביצועים מעולים

## 🎉 סיכום

הקוד עכשיו:
- **נקי** - אין כפילויות או קונפליקטים
- **יעיל** - state management נכון
- **אמין** - עובד בכל התרחישים
- **מקצועי** - עקרונות React/Compose נכונים
