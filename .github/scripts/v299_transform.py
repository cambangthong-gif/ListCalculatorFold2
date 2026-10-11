from pathlib import Path

p=Path("app/src/main/java/com/vinh/listcalculatorfold2/MainActivity.java")
s=p.read_text()

def rep(old,new,n=1):
    global s
    if old not in s:
        raise SystemExit("Missing anchor: "+old[:120].replace("\n"," "))
    s=s.replace(old,new,n)

s=s.replace("uri=data.getData();\n                      if(uri!=null){",
            "uri=data.getData();\n                      pendingOcrImageUri=uri;\n                      if(uri!=null){",1)

marker="void showOcrReviewWithRaw(OcrSheet sh,String raw,boolean uncertain){"
idx=s.find(marker)
if idx<0:
    raise SystemExit("showOcrReviewWithRaw not found")
line=s.rfind("\n",0,idx)+1
indent=s[line:idx]
helper=indent+r'''ArrayList<String> ocrNumberChoices(String raw){
        LinkedHashSet<String> set=new LinkedHashSet<>();
        if(raw!=null){
            String[] parts=raw.split("[\\s,;:|]+");
            for(String part:parts){
                String v=cleanOcrNumber(part);
                if(v==null||v.isEmpty())continue;
                if(v.length()==1 && "123456".contains(v))continue;
                if(v.length()<=8)set.add(v);
            }
        }
        return new ArrayList<>(set);
    }

'''
s=s[:line]+helper+s[line:]

rep("""        final LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();
        for(Map.Entry<String,String> e:sh.values.entrySet()){""",
"""        final LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();
        final ArrayList<String> allChoices=ocrNumberChoices(raw);
        for(Map.Entry<String,String> e:sh.values.entrySet()){""")

rep("""            EditText val=new EditText(this);val.setSingleLine();val.setText(e.getValue());val.setTextSize(foldInnerLayout?20:17);
            val.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            val.setHint("?");
            val.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            GradientDrawable bg=new GradientDrawable();
            bg.setColor((e.getValue()==null||e.getValue().isEmpty())?Color.rgb(255,247,237):Color.WHITE);
            bg.setStroke(dp(1),(e.getValue()==null||e.getValue().isEmpty())?Color.rgb(251,146,60):Color.rgb(218,220,224));
            bg.setCornerRadius(dp(12));val.setBackground(bg);val.setPadding(dp(8),0,dp(8),0);
            row.addView(name,new LinearLayout.LayoutParams(0,dp(50),1.55f));
            row.addView(val,new LinearLayout.LayoutParams(0,dp(46),.75f));
            box.addView(row);
            fields.put(e.getKey(),val);""",
"""            String detected=e.getValue()==null?"":e.getValue();
            if(detected.length()==1 && "123456".contains(detected))detected="";
            EditText val=new EditText(this);val.setSingleLine();val.setText(detected);val.setTextSize(foldInnerLayout?20:17);
            val.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            val.setHint("?");
            val.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            GradientDrawable bg=new GradientDrawable();
            bg.setColor(detected.isEmpty()?Color.rgb(255,247,237):Color.WHITE);
            bg.setStroke(dp(1),detected.isEmpty()?Color.rgb(251,146,60):Color.rgb(218,220,224));
            bg.setCornerRadius(dp(12));val.setBackground(bg);val.setPadding(dp(8),0,dp(8),0);

            Button choose=smallActionButton("Chọn");
            choose.setEnabled(!allChoices.isEmpty());
            choose.setAlpha(choose.isEnabled()?1f:.35f);
            choose.setOnClickListener(v->{
                haptic(v);
                PopupMenu pm=new PopupMenu(this,choose);
                for(String x:allChoices)pm.getMenu().add(x);
                pm.setOnMenuItemClickListener(mi->{val.setText(mi.getTitle());return true;});
                pm.show();
            });

            row.addView(name,new LinearLayout.LayoutParams(0,dp(50),1.35f));
            row.addView(val,new LinearLayout.LayoutParams(0,dp(46),.62f));
            row.addView(choose,new LinearLayout.LayoutParams(dp(72),dp(42)));
            box.addView(row);
            fields.put(e.getKey(),val);""")

s=s.replace("Ô màu cam là số OCR chưa đọc chắc. Với số viết tay, luôn đối chiếu ảnh trước khi nhập.",
            "Nếu nhận sai, bấm Chọn để lấy số OCR khác trong ảnh hoặc gõ sửa trực tiếp. Số 1–6 nghi là STT sẽ không tự điền.",1)
s=s.replace("Phiên bản 2.9.8","Phiên bản 2.9.9")
p.write_text(s)
