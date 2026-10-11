from pathlib import Path
import re

p=Path("app/src/main/java/com/vinh/listcalculatorfold2/MainActivity.java")
s=p.read_text()

def must_replace(old,new,count=1):
    global s
    if old not in s:
        raise SystemExit("Missing anchor: "+old[:100].replace("\n"," "))
    s=s.replace(old,new,count)

s=s.replace("uri=data.getData();\n                      if(uri!=null){",
            "uri=data.getData();\n                      pendingOcrImageUri=uri;\n                      if(uri!=null){",1)

must_replace("""        final LinkedHashMap<String,String> values=new LinkedHashMap<>();
        OcrSheet(){""","""        final LinkedHashMap<String,String> values=new LinkedHashMap<>();
        final LinkedHashMap<String,ArrayList<String>> candidates=new LinkedHashMap<>();
        OcrSheet(){""")

must_replace("""            values.put("Nguyễn Thị Hường","");
        }""","""            values.put("Nguyễn Thị Hường","");
            for(String k:values.keySet())candidates.put(k,new ArrayList<>());
        }""")

# Normal parser chooser.
ms=s.find("    ArrayList<OcrSheet> parseCancelSheets(")
me=s.find("    OcrSheet buildFallbackOcrSheet(",ms)
if ms<0 or me<0:
    raise SystemExit("parseCancelSheets/buildFallback boundaries missing")
chunk=s[ms:me]
pat=r'''OcrToken bestNum=null;\s*float bestScore=Float\.MAX_VALUE;\s*for\(OcrToken n:numbers\)\{.*?\}\s*if\(bestNum!=null\)sh\.values\.put\(agent,bestNum\.text\);'''
repl='''OcrToken bestNum=null;
                float bestScore=Float.MAX_VALUE;
                ArrayList<String> opts=sh.candidates.get(agent);
                for(OcrToken n:numbers){
                    if(Math.abs(n.cy()-y)>yTol)continue;
                    if(Math.abs(n.cx()-h.cx())>=iw*.20f)continue;
                    String val=n.text;
                    if(val==null||val.isEmpty())continue;
                    if(!opts.contains(val))opts.add(val);
                    boolean rowNo=val.length()==1 && "123456".contains(val);
                    float score=Math.abs(n.cx()-h.cx()) + Math.abs(n.cy()-y)*1.8f;
                    if(rowNo)score+=iw*.45f;
                    if(val.length()>=2)score-=iw*.06f;
                    if(score<bestScore){bestScore=score;bestNum=n;}
                }
                if(bestNum!=null){
                    String v=bestNum.text;
                    if(!(v.length()==1 && "123456".contains(v)))sh.values.put(agent,v);
                }'''
chunk,n=re.subn(pat,repl,chunk,count=1,flags=re.S)
if n!=1:
    raise SystemExit("normal parser chooser not found")
s=s[:ms]+chunk+s[me:]

# Fallback chooser.
ms=s.find("buildFallbackOcrSheet(")
me=s.find("showOcrReviewWithRaw(",ms)
if ms<0 or me<0:
    raise SystemExit("fallback/review boundaries missing")
chunk=s[ms:me]
pat=r'''float yTol=Math\.max\(24,ih\*\.022f\);\s*for\(Map\.Entry<String,Float> e:rowY\.entrySet\(\)\)\{.*?\}\s*return sh;'''
repl='''float yTol=Math.max(24,ih*.022f);
              for(Map.Entry<String,Float> e:rowY.entrySet()){
                  OcrToken best=null;float bestScore=Float.MAX_VALUE;
                  ArrayList<String> opts=sh.candidates.get(e.getKey());
                  for(OcrToken n:numbers){
                      if(Math.abs(n.cy()-e.getValue())>yTol)continue;
                      if(n.cx()<iw*.45f || n.cx()>iw*.86f)continue;
                      String val=n.text;
                      if(val==null||val.isEmpty())continue;
                      if(!opts.contains(val))opts.add(val);
                      boolean rowNo=val.length()==1 && "123456".contains(val);
                      float score=Math.abs(n.cx()-cancelX)+Math.abs(n.cy()-e.getValue())*2f;
                      if(rowNo)score+=iw*.50f;
                      if(val.length()>=2)score-=iw*.08f;
                      if(score<bestScore){bestScore=score;best=n;}
                  }
                  if(best!=null){
                      String v=best.text;
                      if(!(v.length()==1 && "123456".contains(v)))sh.values.put(e.getKey(),v);
                  }
              }
              return sh;'''
chunk,n=re.subn(pat,repl,chunk,count=1,flags=re.S)
if n!=1:
    raise SystemExit("fallback chooser not found")
s=s[:ms]+chunk+s[me:]

# Preserve candidate lists after edit.
must_replace("""        OcrSheet s=new OcrSheet();s.province=base.province;s.headerX=base.headerX;s.headerY=base.headerY;
        for(Map.Entry<String,EditText> e:fields.entrySet())s.values.put(e.getKey(),cleanOcrNumber(e.getValue().getText().toString()));
        return s;""","""        OcrSheet s=new OcrSheet();s.province=base.province;s.headerX=base.headerX;s.headerY=base.headerY;
        for(Map.Entry<String,EditText> e:fields.entrySet()){
            s.values.put(e.getKey(),cleanOcrNumber(e.getValue().getText().toString()));
            ArrayList<String> src=base.candidates.get(e.getKey());
            if(src!=null)s.candidates.put(e.getKey(),new ArrayList<>(src));
        }
        return s;""")

# Add per-row candidate picker in review.
must_replace("""                  row.addView(name,new LinearLayout.LayoutParams(0,dp(50),1.55f));
                  row.addView(val,new LinearLayout.LayoutParams(0,dp(46),.75f));
                  box.addView(row);fields.put(e.getKey(),val);""","""                  row.addView(name,new LinearLayout.LayoutParams(0,dp(50),1.45f));
                  row.addView(val,new LinearLayout.LayoutParams(0,dp(46),.62f));

                  Button choose=smallActionButton("Chọn");
                  ArrayList<String> opts=sh.candidates.get(e.getKey());
                  choose.setEnabled(opts!=null&&!opts.isEmpty());
                  choose.setAlpha(choose.isEnabled()?1f:.35f);
                  choose.setOnClickListener(v->{
                      haptic(v);
                      ArrayList<String> list=sh.candidates.get(e.getKey());
                      if(list==null||list.isEmpty())return;
                      PopupMenu pm=new PopupMenu(this,choose);
                      for(String x:list)pm.getMenu().add(x);
                      pm.setOnMenuItemClickListener(mi->{val.setText(mi.getTitle());return true;});
                      pm.show();
                  });
                  row.addView(choose,new LinearLayout.LayoutParams(dp(72),dp(42)));
                  box.addView(row);fields.put(e.getKey(),val);""")

s=s.replace("Ô màu cam là số OCR chưa đọc chắc. Với số viết tay, luôn đối chiếu ảnh trước khi nhập.",
            "Ô màu cam là số OCR chưa chắc. Bấm Chọn để lấy số OCR khác cùng dòng, hoặc gõ sửa trực tiếp.",1)
s=s.replace("Phiên bản 2.9.8","Phiên bản 2.9.9")

p.write_text(s)
