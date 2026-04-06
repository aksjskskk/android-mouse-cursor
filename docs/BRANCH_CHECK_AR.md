# التحقق من اسم الفرع الحقيقي

إذا ظهر عندي أن الفرع الحالي هو `work` بينما لا يظهر لديك نفس الاسم، فهذا غالباً يعني أحد الأسباب التالية:

1. نحن نعمل على نسخة/clone مختلفة من المستودع.
2. الفرع موجود محلياً فقط عندي ولم يتم دفعه إلى remote.
3. أنت على remote مختلف (مثلاً fork آخر).

الأوامر الأدق للتحقق:

```bash
git branch --show-current
git rev-parse --abbrev-ref HEAD
git branch -vv
git remote -v
```

الاسم الكامل للفرع المحلي يكون عادة:

- `refs/heads/work`

وليس اسم remote إلا إذا كان موجوداً كـ:

- `refs/remotes/origin/work`
