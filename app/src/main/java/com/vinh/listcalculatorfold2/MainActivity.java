package com.vinh.listcalculatorfold2;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.*;
import android.text.method.DigitsKeyListener;
import java.io.*;
import java.text.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    static final String PREFS = "list_calculator_fold_2";
    static final String DATA = "data_v1";
    static final String UNGROUPED = "";
    final ArrayList<GroupModel> groups = new ArrayList<>();
    final ArrayList<TableModel> tables = new ArrayList<>();
    final HashMap<String, TextView> groupTotalViews = new HashMap<>();
    LinearLayout page;
    int blue = Color.rgb(36,87,214), bg = Color.rgb(246,247,251), line = Color.rgb(225,228,235), red = Color.rgb(185,45,45);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        load();
        buildScreen();
    }

    void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(Color.WHITE);
        TextView title = text("List Calculator Fold", 21, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button add = button("＋ Thêm");
        Button group = button("Nhóm");
        Button share = button("Chia sẻ ảnh");
        bar.addView(group); bar.addView(add); bar.addView(share);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = getResources().getConfiguration().screenWidthDp >= 700 ? dp(28) : dp(10);
        page.setPadding(pad, dp(10), pad, dp(80));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        add.setOnClickListener(v -> { haptic(v); showAddMenu(add); });
        group.setOnClickListener(v -> { haptic(v); createGroupDialog(); });
        share.setOnClickListener(v -> { haptic(v); shareAsImage(); });
        render();
    }

    void showAddMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add("Bảng tính");
        p.getMenu().add("Bảng hủy vé");
        p.setOnMenuItemClickListener(i -> {
            if (i.getTitle().toString().contains("hủy")) addCancelTable(); else addCalcTable();
            return true;
        }); p.show();
    }

    void addCalcTable() {
        TableModel t = new TableModel(); t.id = id(); t.type = "calc"; t.title = "Bảng tính " + (tables.size()+1); t.groupId = UNGROUPED;
        t.values.add(0d); tables.add(t); save(); render();
    }

    void addCancelTable() {
        TableModel t = new TableModel(); t.id = id(); t.type = "cancel"; t.title = "Bảng hủy vé"; t.groupId = UNGROUPED;
        t.cancelRows.add(new CancelRow("",0)); tables.add(t); save(); render();
    }

    void createGroupDialog() {
        final EditText e = edit("Tên nhóm"); e.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("Tạo nhóm bảng").setView(wrap(e))
            .setPositiveButton("Tạo", (d,w) -> {
                String n = e.getText().toString().trim(); if (n.isEmpty()) return;
                GroupModel g = new GroupModel(); g.id=id(); g.name=n; groups.add(g); save(); render();
            }).setNegativeButton("Hủy", null).show();
    }

    void render() {
        page.removeAllViews(); groupTotalViews.clear();
        addSection(UNGROUPED, "Chưa nhóm", false);
        for (GroupModel g: groups) addSection(g.id, g.name, true);
        if (tables.isEmpty()) {
            TextView empty=text("Chưa có bảng. Bấm “＋ Thêm” để tạo bảng tính hoặc bảng hủy vé.",16,false);
            empty.setGravity(Gravity.CENTER); empty.setTextColor(Color.DKGRAY); empty.setPadding(dp(20),dp(55),dp(20),dp(55)); page.addView(empty);
        }
    }

    void addSection(String groupId, String name, boolean realGroup) {
        ArrayList<TableModel> list = inGroup(groupId);
        if (!realGroup && list.isEmpty()) return;
        LinearLayout section = new LinearLayout(this); section.setOrientation(LinearLayout.VERTICAL); section.setPadding(0,0,0,dp(12));
        GradientDrawable sectionBg = round(Color.WHITE, dp(16)); section.setBackground(sectionBg);
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL); head.setPadding(dp(14),dp(10),dp(10),dp(8));
        TextView n = text(name, realGroup?18:15,true); head.addView(n,new LinearLayout.LayoutParams(0,-2,1));
        if (realGroup) {
            TextView total = text("Tổng nhóm: "+fmt(groupTotal(groupId)),16,true); total.setTextColor(blue); groupTotalViews.put(groupId,total); head.addView(total);
            ImageButton menu = iconButton("⋮"); head.addView(menu); menu.setOnClickListener(v->{haptic(v); showGroupMenu(v, groupId);});
        }
        section.addView(head);
        LinearLayout listBox = new LinearLayout(this); listBox.setOrientation(LinearLayout.VERTICAL); listBox.setPadding(dp(8),0,dp(8),dp(8));
        listBox.setOnDragListener((v,e)->handleDrop(listBox,groupId,e));
        for(TableModel t:list) listBox.addView(buildCard(t));
        if (realGroup && list.isEmpty()) {
            TextView hint=text("Kéo bảng vào đây hoặc chọn “Chuyển nhóm” trong menu của bảng.",14,false); hint.setTextColor(Color.GRAY); hint.setPadding(dp(10),dp(10),dp(10),dp(18)); listBox.addView(hint);
        }
        section.addView(listBox); page.addView(section,new LinearLayout.LayoutParams(-1,-2){ {bottomMargin=dp(12);} });
    }

    boolean handleDrop(LinearLayout box, String targetGroup, android.view.DragEvent e) {
        switch(e.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_ENTERED: box.setAlpha(.82f); return true;
            case android.view.DragEvent.ACTION_DRAG_EXITED: box.setAlpha(1f); return true;
            case android.view.DragEvent.ACTION_DROP:
                box.setAlpha(1f); Object state=e.getLocalState(); if(!(state instanceof String)) return true;
                TableModel moving=findTable((String)state); if(moving==null)return true;
                TableModel before=null;
                float y=e.getY();
                for(int i=0;i<box.getChildCount();i++) {
                    View c=box.getChildAt(i); Object tag=c.getTag();
                    if(tag instanceof String && y < c.getTop()+c.getHeight()/2f) { before=findTable((String)tag); break; }
                }
                tables.remove(moving); moving.groupId=targetGroup;
                if(before!=null && before!=moving) tables.add(Math.max(0,tables.indexOf(before)),moving);
                else {
                    int idx=-1; for(int i=0;i<tables.size();i++) if(targetGroup.equals(tables.get(i).groupId)) idx=i;
                    tables.add(idx+1,moving);
                }
                save(); render(); return true;
            case android.view.DragEvent.ACTION_DRAG_ENDED: box.setAlpha(1f); return true;
            default:return true;
        }
    }

    View buildCard(TableModel t) {
        LinearLayout card=new LinearLayout(this); card.setTag(t.id); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(10),dp(8),dp(10),dp(10));
        GradientDrawable d=round(Color.rgb(251,252,254),dp(12)); d.setStroke(dp(1),line); card.setBackground(d);
        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView drag=text("☰",22,true); drag.setGravity(Gravity.CENTER); drag.setPadding(dp(8),dp(7),dp(14),dp(7));
        TextView title=text(t.title,17,true); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView total=text(fmt(t.total()),17,true); total.setTextColor(blue);
        ImageButton menu=iconButton("⋮");
        head.addView(drag,new LinearLayout.LayoutParams(dp(48),dp(48))); head.addView(title,new LinearLayout.LayoutParams(0,-2,1)); head.addView(total); head.addView(menu);
        card.addView(head);
        drag.setOnLongClickListener(v->{haptic(v); ClipData data=ClipData.newPlainText("table",t.id); v.startDragAndDrop(data,new View.DragShadowBuilder(card),t.id,0); return true;});
        drag.setOnTouchListener((v,e)->{ if(e.getAction()==MotionEvent.ACTION_DOWN) Toast.makeText(this,"Nhấn giữ rồi kéo bảng",Toast.LENGTH_SHORT).show(); return false; });
        title.setOnClickListener(v->{haptic(v); renameTable(t);});
        menu.setOnClickListener(v->{haptic(v); showTableMenu(v,t);});
        if("cancel".equals(t.type)) buildCancelBody(card,t,total); else buildCalcBody(card,t,total);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(8); card.setLayoutParams(lp); return card;
    }

    void buildCalcBody(LinearLayout card, TableModel t, TextView totalView) {
        for(int i=0;i<t.values.size();i++) {
            final int idx=i; LinearLayout r=row(); EditText e=numEdit(t.values.get(i)==0?"":plain(t.values.get(i))); Button del=smallButton("×");
            r.addView(e,new LinearLayout.LayoutParams(0,dp(48),1)); r.addView(del,new LinearLayout.LayoutParams(dp(48),dp(48))); card.addView(r);
            e.addTextChangedListener(simple(s->{ t.values.set(idx,parseNum(s)); totalView.setText(fmt(t.total())); updateGroupTotal(t.groupId); save(); }));
            del.setOnClickListener(v->{haptic(v); if(t.values.size()>1)t.values.remove(idx); else t.values.set(0,0d); save(); render();});
        }
        Button add=smallButton("＋ Thêm số"); card.addView(add); add.setOnClickListener(v->{haptic(v);t.values.add(0d);save();render();});
    }

    void buildCancelBody(LinearLayout card, TableModel t, TextView totalView) {
        final TextView[] sumRef = new TextView[1];
        LinearLayout h=row(); TextView a=text("Tên đại lý",13,true), q=text("Số lượng",13,true); q.setGravity(Gravity.CENTER);
        h.addView(a,new LinearLayout.LayoutParams(0,-2,2)); h.addView(q,new LinearLayout.LayoutParams(0,-2,1)); h.addView(new Space(this),new LinearLayout.LayoutParams(dp(48),1)); card.addView(h);
        for(int i=0;i<t.cancelRows.size();i++) {
            final int idx=i; CancelRow cr=t.cancelRows.get(i); LinearLayout r=row(); EditText name=edit("Tên đại lý"); name.setText(cr.agent); EditText qty=numEdit(cr.qty==0?"":String.valueOf(cr.qty)); Button del=smallButton("×");
            r.addView(name,new LinearLayout.LayoutParams(0,dp(48),2)); r.addView(qty,new LinearLayout.LayoutParams(0,dp(48),1)); r.addView(del,new LinearLayout.LayoutParams(dp(48),dp(48))); card.addView(r);
            name.addTextChangedListener(simple(s->{cr.agent=s;save();}));
            qty.addTextChangedListener(simple(s->{cr.qty=(long)parseNum(s);totalView.setText(fmt(t.total()));if(sumRef[0]!=null)sumRef[0].setText("TỔNG: "+fmt(t.total()));updateGroupTotal(t.groupId);save();}));
            del.setOnClickListener(v->{haptic(v);if(t.cancelRows.size()>1)t.cancelRows.remove(idx);else {cr.agent="";cr.qty=0;}save();render();});
        }
        LinearLayout foot=row(); Button add=smallButton("＋ Thêm đại lý"); TextView sum=text("TỔNG: "+fmt(t.total()),15,true); sumRef[0]=sum; sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        foot.addView(add,new LinearLayout.LayoutParams(0,dp(46),1)); foot.addView(sum,new LinearLayout.LayoutParams(0,dp(46),1)); card.addView(foot);
        add.setOnClickListener(v->{haptic(v);t.cancelRows.add(new CancelRow("",0));save();render();});
    }

    void showTableMenu(View anchor, TableModel t) {
        PopupMenu p=new PopupMenu(this,anchor); p.getMenu().add("Đổi tên"); p.getMenu().add("Chuyển nhóm"); p.getMenu().add("Chia sẻ bảng bằng ảnh"); p.getMenu().add("Xóa bảng");
        p.setOnMenuItemClickListener(i->{String s=i.getTitle().toString(); if(s.startsWith("Đổi"))renameTable(t); else if(s.startsWith("Chuyển"))moveGroupDialog(t); else if(s.startsWith("Chia"))shareAsImage(t); else deleteTable(t); return true;}); p.show();
    }

    void showGroupMenu(View anchor,String gid) {
        PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("Đổi tên nhóm");p.getMenu().add("Xóa nhóm (giữ các bảng)");
        p.setOnMenuItemClickListener(i->{if(i.getTitle().toString().startsWith("Đổi"))renameGroup(gid);else deleteGroup(gid);return true;});p.show();
    }

    void renameTable(TableModel t) { EditText e=edit("Tên bảng");e.setText(t.title);new AlertDialog.Builder(this).setTitle("Đổi tên bảng").setView(wrap(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){t.title=s;save();render();}}).setNegativeButton("Hủy",null).show(); }
    void renameGroup(String gid) { GroupModel g=findGroup(gid);if(g==null)return;EditText e=edit("Tên nhóm");e.setText(g.name);new AlertDialog.Builder(this).setTitle("Đổi tên nhóm").setView(wrap(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){g.name=s;save();render();}}).setNegativeButton("Hủy",null).show(); }
    void deleteTable(TableModel t) { new AlertDialog.Builder(this).setTitle("Xóa bảng?").setMessage(t.title).setPositiveButton("Xóa",(d,w)->{tables.remove(t);save();render();}).setNegativeButton("Hủy",null).show(); }
    void deleteGroup(String gid) { GroupModel g=findGroup(gid);if(g==null)return;new AlertDialog.Builder(this).setTitle("Xóa nhóm?").setMessage("Các bảng trong nhóm sẽ được chuyển về Chưa nhóm.").setPositiveButton("Xóa",(d,w)->{for(TableModel t:tables)if(gid.equals(t.groupId))t.groupId=UNGROUPED;groups.remove(g);save();render();}).setNegativeButton("Hủy",null).show(); }

    void moveGroupDialog(TableModel t) {
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);
        new AlertDialog.Builder(this).setTitle("Chuyển bảng vào nhóm").setItems(names.toArray(new String[0]),(d,which)->{t.groupId=which==0?UNGROUPED:groups.get(which-1).id;save();render();}).show();
    }

    void updateGroupTotal(String gid) { TextView v=groupTotalViews.get(gid); if(v!=null)v.setText("Tổng nhóm: "+fmt(groupTotal(gid))); }
    double groupTotal(String gid){double x=0;for(TableModel t:tables)if(gid.equals(t.groupId))x+=t.total();return x;}
    ArrayList<TableModel> inGroup(String gid){ArrayList<TableModel>x=new ArrayList<>();for(TableModel t:tables)if(gid.equals(t.groupId))x.add(t);return x;}
    TableModel findTable(String id){for(TableModel t:tables)if(t.id.equals(id))return t;return null;}
    GroupModel findGroup(String id){for(GroupModel g:groups)if(g.id.equals(id))return g;return null;}

    void shareAsImage(){shareAsImage(null);}
    void shareAsImage(TableModel only) {
        try {
            LinearLayout report=buildShareReport(only); int width=Math.min(dp(900),Math.max(dp(520),getResources().getDisplayMetrics().widthPixels));
            report.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            report.layout(0,0,width,report.getMeasuredHeight());
            Bitmap bmp=Bitmap.createBitmap(width,Math.max(1,report.getMeasuredHeight()),Bitmap.Config.ARGB_8888); Canvas c=new Canvas(bmp);c.drawColor(Color.WHITE);report.draw(c);
            File dir=new File(getCacheDir(),"share");dir.mkdirs();File f=new File(dir,"list-calculator-"+System.currentTimeMillis()+".png");try(FileOutputStream os=new FileOutputStream(f)){bmp.compress(Bitmap.CompressFormat.PNG,100,os);} bmp.recycle();
            Uri uri=Uri.parse("content://com.vinh.listcalculatorfold2.share/"+Uri.encode(f.getName()));
            Intent send=new Intent(Intent.ACTION_SEND);send.setType("image/png");send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Chia sẻ ảnh"));
        }catch(Exception ex){Toast.makeText(this,"Không tạo được ảnh: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }

    LinearLayout buildShareReport(TableModel only) {
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(28),dp(26),dp(28),dp(30));r.setBackgroundColor(Color.WHITE);
        TextView h=text(only==null?"TỔNG HỢP BẢNG TÍNH":only.title,24,true);h.setTextColor(Color.BLACK);r.addView(h);
        TextView date=text(new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date()),13,false);date.setTextColor(Color.GRAY);r.addView(date);
        addGap(r,14);
        if(only!=null){appendShareTable(r,only);return r;}
        for(GroupModel g:groups){ArrayList<TableModel> l=inGroup(g.id);if(l.isEmpty())continue;TextView gh=text(g.name+"  •  Tổng nhóm: "+fmt(groupTotal(g.id)),19,true);gh.setTextColor(blue);r.addView(gh);addGap(r,6);for(TableModel t:l)appendShareTable(r,t);addGap(r,10);}
        ArrayList<TableModel> u=inGroup(UNGROUPED);if(!u.isEmpty()){TextView gh=text("CHƯA NHÓM",17,true);r.addView(gh);for(TableModel t:u)appendShareTable(r,t);}
        double all=0;for(TableModel t:tables)all+=t.total();addGap(r,12);TextView grand=text("TỔNG TẤT CẢ: "+fmt(all),22,true);grand.setGravity(Gravity.END);grand.setTextColor(blue);r.addView(grand);return r;
    }

    void appendShareTable(LinearLayout r,TableModel t){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(10),dp(14),dp(12));GradientDrawable gd=round(Color.rgb(250,250,252),dp(10));gd.setStroke(1,line);box.setBackground(gd);
        LinearLayout hh=row();TextView n=text(t.title,17,true);TextView tt=text("Tổng: "+fmt(t.total()),17,true);tt.setGravity(Gravity.END);tt.setTextColor(blue);hh.addView(n,new LinearLayout.LayoutParams(0,-2,1));hh.addView(tt);box.addView(hh);
        if("cancel".equals(t.type)){
            for(CancelRow cr:t.cancelRows){if(cr.agent.trim().isEmpty()&&cr.qty==0)continue;LinearLayout rr=row();TextView a=text(cr.agent.isEmpty()?"—":cr.agent,14,false);TextView q=text(fmt(cr.qty),14,false);q.setGravity(Gravity.END);rr.addView(a,new LinearLayout.LayoutParams(0,-2,2));rr.addView(q,new LinearLayout.LayoutParams(0,-2,1));box.addView(rr);}
        }else{int k=1;for(double v:t.values){if(v==0&&t.values.size()>1)continue;LinearLayout rr=row();TextView a=text(String.valueOf(k++),14,false);a.setTextColor(Color.GRAY);TextView q=text(fmt(v),14,false);q.setGravity(Gravity.END);rr.addView(a,new LinearLayout.LayoutParams(0,-2,1));rr.addView(q,new LinearLayout.LayoutParams(0,-2,3));box.addView(rr);}}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);r.addView(box,lp);
    }

    void save(){
        try{JSONObject root=new JSONObject();JSONArray ga=new JSONArray();for(GroupModel g:groups)ga.put(g.json());JSONArray ta=new JSONArray();for(TableModel t:tables)ta.put(t.json());root.put("groups",ga);root.put("tables",ta);getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(DATA,root.toString()).apply();}catch(Exception ignored){}
    }
    void load(){
        String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(DATA,null);if(s==null)return;try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++)tables.add(TableModel.from(ta.getJSONObject(i)));}catch(Exception ignored){}
    }

    static class GroupModel{String id,name;JSONObject json()throws Exception{return new JSONObject().put("id",id).put("name",name);}static GroupModel from(JSONObject o){GroupModel g=new GroupModel();g.id=o.optString("id");g.name=o.optString("name","Nhóm");return g;}}
    static class CancelRow{String agent;long qty;CancelRow(String a,long q){agent=a;qty=q;}JSONObject json()throws Exception{return new JSONObject().put("agent",agent).put("qty",qty);}static CancelRow from(JSONObject o){return new CancelRow(o.optString("agent"),o.optLong("qty"));}}
    static class TableModel{
        String id,type="calc",title="Bảng tính",groupId="";ArrayList<Double> values=new ArrayList<>();ArrayList<CancelRow> cancelRows=new ArrayList<>();
        double total(){if("cancel".equals(type)){long x=0;for(CancelRow r:cancelRows)x+=r.qty;return x;}double x=0;for(Double v:values)x+=v;return x;}
        JSONObject json()throws Exception{JSONObject o=new JSONObject().put("id",id).put("type",type).put("title",title).put("groupId",groupId);JSONArray v=new JSONArray();for(Double d:values)v.put(d);o.put("values",v);JSONArray c=new JSONArray();for(CancelRow r:cancelRows)c.put(r.json());o.put("cancelRows",c);return o;}
        static TableModel from(JSONObject o){TableModel t=new TableModel();t.id=o.optString("id");t.type=o.optString("type","calc");t.title=o.optString("title","Bảng tính");t.groupId=o.optString("groupId","");JSONArray v=o.optJSONArray("values");if(v!=null)for(int i=0;i<v.length();i++)t.values.add(v.optDouble(i));JSONArray c=o.optJSONArray("cancelRows");if(c!=null)for(int i=0;i<c.length();i++)t.cancelRows.add(CancelRow.from(c.optJSONObject(i)));if(t.values.isEmpty()&&"calc".equals(t.type))t.values.add(0d);if(t.cancelRows.isEmpty()&&"cancel".equals(t.type))t.cancelRows.add(new CancelRow("",0));return t;}
    }

    TextWatcher simple(final Str cb){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){}public void onTextChanged(CharSequence s,int a,int b,int c){cb.go(s.toString());}public void afterTextChanged(Editable e){}};} interface Str{void go(String s);}
    TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(25,27,32));v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,1);v.setPadding(dp(4),dp(4),dp(4),dp(4));return v;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(0);b.setMinWidth(0);return b;}
    Button smallButton(String s){Button b=button(s);b.setPadding(dp(8),0,dp(8),0);return b;}
    ImageButton iconButton(String s){ImageButton b=new ImageButton(this);b.setBackgroundColor(Color.TRANSPARENT);b.setContentDescription(s);TextView dummy=null; Bitmap bm=Bitmap.createBitmap(dp(38),dp(38),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bm);Paint p=new Paint(1);p.setColor(Color.DKGRAY);p.setTextSize(dp(26));p.setTextAlign(Paint.Align.CENTER);c.drawText(s,dp(19),dp(27),p);b.setImageBitmap(bm);b.setPadding(0,0,0,0);b.setLayoutParams(new LinearLayout.LayoutParams(dp(44),dp(44)));return b;}
    EditText edit(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setSingleLine(true);e.setPadding(dp(10),0,dp(10),0);return e;}
    EditText numEdit(String s){EditText e=edit("0");e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);e.setKeyListener(DigitsKeyListener.getInstance("0123456789.,-"));e.setText(s);e.setSelectAllOnFocus(true);return e;}
    LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    View wrap(View v){LinearLayout w=new LinearLayout(this);w.setPadding(dp(22),0,dp(22),0);w.addView(v,new LinearLayout.LayoutParams(-1,-2));return w;}
    GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    void addGap(LinearLayout p,int d){Space s=new Space(this);p.addView(s,new LinearLayout.LayoutParams(1,dp(d)));}
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);} void haptic(View v){v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);}
    String id(){return UUID.randomUUID().toString();}
    double parseNum(String s){try{s=s.trim().replace(" ","");if(s.contains(",")){s=s.replace(".","").replace(',','.');}return s.isEmpty()||s.equals("-")?0:Double.parseDouble(s);}catch(Exception e){return 0;}}
    String fmt(double n){NumberFormat f=NumberFormat.getNumberInstance(new Locale("vi","VN"));f.setMaximumFractionDigits(2);f.setMinimumFractionDigits(0);return f.format(n);}String plain(double n){return n==(long)n?String.valueOf((long)n):String.valueOf(n).replace('.',',');}
}
