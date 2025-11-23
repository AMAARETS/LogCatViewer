# תיקון תפריט הקשר כפול - פתרון סופי

## 🐛 הבעיה

כאשר לוחצים לחיצה ימנית על שורות נבחרות, **שני תפריטי הקשר** נפתחים במקביל:
1. תפריט מ-`ContextMenuArea` (אוטומטי)
2. תפריט מהטיפול הידני ב-right click

## 🔍 הסיבה

הקוד הקודם עטף את `LogItemRow` ב-`ContextMenuArea` גם כשהשורה נבחרת:

```kotlin
// ❌ קוד בעייתי
if (!isSelected) {
    ContextMenuArea(...) {
        LogItemRow(...)
    }
} else {
    LogItemRow(...)  // אבל עדיין יש טיפול ידני ב-right click!
}
```

הבעיה: גם כשהשורה נבחרת, ה-`ContextMenuArea` עדיין היה שם (מהענף הראשון), והטיפול הידני היה בתוך `LogItemRow`.

## ✅ הפתרון

שינוי הלוגיקה כך ש-`ContextMenuArea` **לא קיים בכלל** כשיש בחירה:

### שלב 1: הוספת פרמטר `useContextMenuArea`

```kotlin
@Composable
private fun LogItemRow(
    ...
    useContextMenuArea: Boolean  // ← פרמטר חדש
) {
    val content = @Composable {
        // כל התוכן של השורה
    }
    
    // רק אם מבוקש, עוטפים ב-ContextMenuArea
    if (useContextMenuArea) {
        ContextMenuArea(...) {
            content()
        }
    } else {
        content()  // ללא עטיפה!
    }
}
```

### שלב 2: קריאה נכונה מ-`LogItemWithSelection`

```kotlin
if (isSelected) {
    // שורה נבחרת: ללא ContextMenuArea
    LogItemRow(
        ...
        useContextMenuArea = false,  // ← ללא עטיפה
        onContextMenu = onContextMenu  // ← טיפול ידני בלבד
    )
} else {
    // שורה לא נבחרת: עם ContextMenuArea
    LogItemRow(
        ...
        useContextMenuArea = true,  // ← עם עטיפה
        onContextMenu = null  // ← אין טיפול ידני
    )
}
```

## 🎯 איך זה עובד עכשיו

### תרחיש 1: לחיצה ימנית על שורה לא נבחרת
```
1. useContextMenuArea = true
2. ContextMenuArea מטפל בלחיצה
3. מציג תפריט עם:
   - "העתק הודעה"
   - "העתק שורה מלאה"
4. ✅ רק תפריט אחד
```

### תרחיש 2: לחיצה ימנית על שורה נבחרת
```
1. useContextMenuArea = false
2. אין ContextMenuArea בכלל!
3. הטיפול הידני ב-PointerEventType.Press מזהה right click
4. קורא ל-onContextMenu(offset)
5. ה-parent פותח DropdownMenu
6. מציג תפריט עם:
   - "העתק X שורות"
   - "בטל בחירה"
7. ✅ רק תפריט אחד
```

## 📊 השוואה

| מצב | לפני | אחרי |
|-----|------|------|
| **שורה לא נבחרת** | 1 תפריט ✅ | 1 תפריט ✅ |
| **שורה נבחרת** | 2 תפריטים ❌ | 1 תפריט ✅ |

## 🔑 העיקרון

**הפרדה מוחלטת:**
- שורות לא נבחרות → `ContextMenuArea` בלבד
- שורות נבחרות → טיפול ידני בלבד
- **אף פעם לא שניהם ביחד!**

## ✅ תוצאה

- ✅ רק תפריט אחד מופיע בכל מצב
- ✅ התפריט נשאר פתוח עד שבוחרים אופציה
- ✅ אין בלבול או כפילויות
- ✅ חווית משתמש מושלמת

## 🎉 סיכום

הבעיה נפתרה לחלוטין על ידי הבנה נכונה של מתי להשתמש ב-`ContextMenuArea` ומתי לא. 

**הכלל הזהב:** אם יש טיפול ידני ב-right click, אין צורך ב-`ContextMenuArea`!
