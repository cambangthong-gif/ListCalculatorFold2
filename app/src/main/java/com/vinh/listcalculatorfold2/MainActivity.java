package com.vinh.listcalculatorfold2;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    static final String PREFS="list_calculator_fold_2", DATA="data_v2", OLD_DATA="data_v1", UNGROUPED="";
    final ArrayList<GroupModel> groups=new ArrayList<>();
    final ArrayList<TableModel> tables=new ArrayList<>();
    LinearLayout sidebar, gridHost, keypadHost;
    TextView pageIndicator, grandTotal;
    Button tableBtn, undoBtn, quick1000;
    String selectedId=null;
    int activeRow=0;
    String activeField="price";
    TableModel lastDeleted=null; int lastDeletedIndex=-1;
    int navy=Color.rgb(28,62,96), navy2=Color.rgb(48,92,136), pale=Color.rgb(225,236,248), paper=Color.rgb(255,250,226), ink=Color.rgb(42,42,42), rule=Color.rgb(218,208,172), red=Color.rgb(205,35,35);

    @Override public void onCreate(Bundle b){super.onCreate(b);load();if(tables.isEmpty())addCalcTable(false);selectedId=tables.get(0).id;buildScreen();}

    void buildScreen(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(navy2);
        LinearLayout top=new LinearLayout(this);top.setPadding(dp(8),dp(8),dp(8),dp(8));top.setGravity(Gravity.CENTER_VERTICAL);top.setBackgroundColor(navy);
        tableBtn=topButton("Bảng ("+tables.size()+")"); Button del=topButton("Xóa bảng"); undoBtn=topButton("Hoàn tác"); Button share=topButton("↗"); quick1000=topButton("1.000"); Button add=topButton("+ Bảng");
        top.addView(tableBtn,w(0,dp(62),1));top.addView(del,w(0,dp(62),1));top.addView(undoBtn,w(0,dp(62),1));top.addView(share,w(0,dp(62),1));top.addView(quick1000,w(0,dp(62),1));top.addView(add,w(0,dp(62),1));root.addView(top);

        LinearLayout middle=new LinearLayout(this);middle.setOrientation(LinearLayout.HORIZONTAL);middle.setBackgroundColor(paper);
        ScrollView leftScroll=new ScrollView(this);leftScroll.setFillViewport(true);sidebar=new LinearLayout(this);sidebar.setOrientation(LinearLayout.VERTICAL);sidebar.setBackgroundColor(Color.rgb(235,243,252));leftScroll.addView(sidebar);
        int sideDp=getResources().getConfiguration().screenWidthDp>=700?225:180;middle.addView(leftScroll,new LinearLayout.LayoutParams(dp(sideDp),-1));
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setBackgroundColor(paper);gridHost=new LinearLayout(this);gridHost.setOrientation(LinearLayout.VERTICAL);right.addView(gridHost,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(8),0,dp(10),0);pageIndicator=text("1/1",13,false);grandTotal=text("0",24,true);grandTotal.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);footer.addView(pageIndicator,w(0,dp(58),1));footer.addView(grandTotal,w(0,dp(58),3));right.addView(footer);middle.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        keypadHost=new LinearLayout(this);keypadHost.setOrientation(LinearLayout.HORIZONTAL);keypadHost.setPadding(dp(3),0,dp(3),dp(4));keypadHost.setBackgroundColor(navy2);root.addView(keypadHost,new LinearLayout.LayoutParams(-1,dp(320)));
        setContentView(root);

        add.setOnClickListener(v->{haptic(v);showAddMenu(add);}); del.setOnClickListener(v->{haptic(v);deleteCurrent();}); undoBtn.setOnClickListener(v->{haptic(v);undoDelete();}); share.setOnClickListener(v->{haptic(v);shareCurrent();}); quick1000.setOnClickListener(v->{haptic(v);setFocused1000();}); tableBtn.setOnClickListener(v->{haptic(v);showTableMenu(tableBtn);});
        renderAll();
    }

    void renderAll(){if(selected()==null&&!tables.isEmpty())selectedId=tables.get(0).id;tableBtn.setText("Bảng ("+tables.size()+")");undoBtn.setEnabled(lastDeleted!=null);renderSidebar();renderGrid();renderKeypads();}

    void renderSidebar(){
        sidebar.removeAllViews();
        addSidebarSection(UNGROUPED,null);
        for(GroupModel g:groups)addSidebarSection(g.id,g);
        sidebar.setOnDragListener((v,e)->true);
    }

    void addSidebarSection(String gid,GroupModel group){
        if(group!=null){
            LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);gh.setPadding(dp(9),dp(8),dp(7),dp(7));gh.setBackgroundColor(Color.rgb(205,223,242));
            TextView n=text("▾ "+group.name,14,true);TextView sum=text(fmt(groupTotal(gid)),13,true);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);gh.addView(n,w(0,dp(40),1));gh.addView(sum,w(dp(82),dp(40),0));gh.setTag("GROUP:"+gid);
            gh.setOnDragListener((v,e)->{if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED){v.setAlpha(.65f);return true;}if(e.getAction()==DragEvent.ACTION_DRAG_EXITED){v.setAlpha(1f);return true;}if(e.getAction()==DragEvent.ACTION_DROP){v.setAlpha(1f);String id=(String)e.getLocalState();TableModel t=findTable(id);if(t!=null){tables.remove(t);t.groupId=gid;tables.add(t);save();renderAll();}return true;}if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)v.setAlpha(1f);return true;});
            gh.setOnLongClickListener(v->{haptic(v);showGroupMenu(v,group);return true;});sidebar.addView(gh);
        }
        ArrayList<TableModel> list=inGroup(gid); for(TableModel t:list)sidebar.addView(sidebarItem(t,gid));
    }

    View sidebarItem(TableModel t,String gid){
        LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setTag(t.id);item.setPadding(dp(14),dp(10),dp(8),dp(10));item.setBackgroundColor(t.id.equals(selectedId)?Color.rgb(32,68,104):Color.TRANSPARENT);
        TextView title=text(t.title,16,true);title.setTextColor(t.id.equals(selectedId)?Color.WHITE:navy);TextView meta=text(timeText(t.updated)+" · "+t.dataRowCount()+" dòng",12,false);meta.setTextColor(t.id.equals(selectedId)?Color.rgb(220,232,245):Color.rgb(90,112,138));item.addView(title);item.addView(meta);
        item.setOnClickListener(v->{selectedId=t.id;activeRow=0;activeField="cancel".equals(t.type)?"qty":"price";renderAll();});
        item.setOnLongClickListener(v->{haptic(v);ClipData cd=ClipData.newPlainText("table",t.id);v.startDragAndDrop(cd,new View.DragShadowBuilder(v),t.id,0);return true;});
        item.setOnDragListener((v,e)->{if(e.getAction()==DragEvent.ACTION_DROP){String movingId=(String)e.getLocalState();TableModel moving=findTable(movingId);if(moving==null||moving==t)return true;tables.remove(moving);moving.groupId=gid;int idx=tables.indexOf(t);tables.add(Math.max(0,idx),moving);save();renderAll();return true;}return true;});
        return item;
    }

    void renderGrid(){
        gridHost.removeAllViews();TableModel t=selected();if(t==null)return;
        if("cancel".equals(t.type))renderCancelGrid(t);else renderCalcGrid(t);
        pageIndicator.setText((tables.indexOf(t)+1)+"/"+tables.size());grandTotal.setText(fmt(t.total()));
    }

    void renderCalcGrid(TableModel t){
        LinearLayout head=gridRow();head.addView(cell("STT",14,false,Gravity.CENTER),w(dp(64),dp(58),0));head.addView(cell("Đơn giá",14,false,Gravity.CENTER),w(0,dp(58),2));head.addView(cell("SL",14,false,Gravity.CENTER),w(0,dp(58),1));head.addView(cell("Thành tiền",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,dp(58),2));gridHost.addView(head);
        ScrollView sv=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);sv.addView(body);gridHost.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        ensureBlankCalc(t);int shown=Math.max(8,t.calcRows.size());for(int i=0;i<shown;i++){final int row=i;CalcRow r=i<t.calcRows.size()?t.calcRows.get(i):null;LinearLayout rr=gridRow();rr.addView(cell(String.valueOf(i+1),14,false,Gravity.CENTER),w(dp(64),dp(56),0));TextView p=cell(r==null||r.price==0?"":plain(r.price),16,false,Gravity.END|Gravity.CENTER_VERTICAL);TextView q=cell(r==null||r.qty==0?"":plain(r.qty),16,false,Gravity.END|Gravity.CENTER_VERTICAL);TextView total=cell(r==null||r.price==0||r.qty==0?"":fmt(r.price*r.qty),16,false,Gravity.END|Gravity.CENTER_VERTICAL);if(row==activeRow&&"price".equals(activeField))p.setTextColor(red);if(row==activeRow&&"qty".equals(activeField))q.setTextColor(red);p.setOnClickListener(v->{activeRow=row;activeField="price";ensureRow(t,row);renderGrid();});q.setOnClickListener(v->{activeRow=row;activeField="qty";ensureRow(t,row);renderGrid();});rr.addView(p,w(0,dp(56),2));rr.addView(q,w(0,dp(56),1));rr.addView(total,w(0,dp(56),2));body.addView(rr);}
    }

    void renderCancelGrid(TableModel t){
        LinearLayout head=gridRow();head.addView(cell("STT",14,false,Gravity.CENTER),w(dp(64),dp(58),0));head.addView(cell("Tên đại lý",14,false,Gravity.START|Gravity.CENTER_VERTICAL),w(0,dp(58),3));head.addView(cell("Số lượng",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,dp(58),2));gridHost.addView(head);
        ScrollView sv=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);sv.addView(body);gridHost.addView(sv,new LinearLayout.LayoutParams(-1,0,1));ensureBlankCancel(t);int shown=Math.max(8,t.cancelRows.size());for(int i=0;i<shown;i++){final int row=i;CancelRow r=i<t.cancelRows.size()?t.cancelRows.get(i):null;LinearLayout rr=gridRow();rr.addView(cell(String.valueOf(i+1),14,false,Gravity.CENTER),w(dp(64),dp(56),0));TextView a=cell(r==null?"":r.agent,16,false,Gravity.START|Gravity.CENTER_VERTICAL);TextView q=cell(r==null||r.qty==0?"":String.valueOf(r.qty),16,false,Gravity.END|Gravity.CENTER_VERTICAL);if(row==activeRow)q.setTextColor(red);a.setOnClickListener(v->{ensureCancelRow(t,row);editAgent(t,row);});q.setOnClickListener(v->{activeRow=row;activeField="qty";ensureCancelRow(t,row);renderGrid();});rr.addView(a,w(0,dp(56),3));rr.addView(q,w(0,dp(56),2));body.addView(rr);}
    }

    void renderKeypads(){keypadHost.removeAllViews();TableModel t=selected();if(t==null)return;if("cancel".equals(t.type)){LinearLayout filler=new LinearLayout(this);keypadHost.addView(filler,w(0,-1,1));keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));}else{keypadHost.addView(buildPad("Đơn giá","price"),w(0,-1,1));keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));}}

    LinearLayout buildPad(String label,String field){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);TextView lab=text(label,14,false);lab.setTextColor(Color.WHITE);lab.setGravity(Gravity.CENTER);wrap.addView(lab,new LinearLayout.LayoutParams(-1,dp(28)));String[][] keys={{"7","8","9"},{"4","5","6"},{"1","2","3"},{"⌫","0","C"}};for(String[] row:keys){LinearLayout rr=new LinearLayout(this);rr.setPadding(0,0,dp(3),dp(3));for(String k:row){Button b=keyButton(k);b.setOnClickListener(v->{haptic(v);handleKey(field,k);});rr.addView(b,w(0,-1,1));}wrap.addView(rr,new LinearLayout.LayoutParams(-1,0,1));}return wrap;
    }

    void handleKey(String field,String key){TableModel t=selected();if(t==null)return;activeField=field;if("cancel".equals(t.type)){ensureCancelRow(t,activeRow);CancelRow r=t.cancelRows.get(activeRow);String s=r.qty==0?"":String.valueOf(r.qty);s=editDigits(s,key);r.qty=parseLong(s);ensureBlankCancel(t);}else{ensureRow(t,activeRow);CalcRow r=t.calcRows.get(activeRow);double cur="price".equals(field)?r.price:r.qty;String s=cur==0?"":plain(cur);s=editDigits(s,key);double val=parseNum(s);if("price".equals(field))r.price=val;else r.qty=val;ensureBlankCalc(t);}t.updated=System.currentTimeMillis();save();renderGrid();renderSidebar();}

    String editDigits(String s,String key){if("C".equals(key))return "";if("⌫".equals(key))return s.length()>0?s.substring(0,s.length()-1):"";return s+key;}
    void setFocused1000(){TableModel t=selected();if(t==null)return;if("cancel".equals(t.type)){ensureCancelRow(t,activeRow);t.cancelRows.get(activeRow).qty=1000;}else{ensureRow(t,activeRow);CalcRow r=t.calcRows.get(activeRow);if("qty".equals(activeField))r.qty=1000;else r.price=1000;}t.updated=System.currentTimeMillis();save();renderGrid();renderSidebar();}

    void showAddMenu(View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("Bảng tính");p.getMenu().add("Bảng hủy vé");p.setOnMenuItemClickListener(i->{if(i.getTitle().toString().contains("hủy"))addCancelTable(true);else addCalcTable(true);return true;});p.show();}
    void showTableMenu(View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("Tạo nhóm");p.getMenu().add("Đổi tên bảng hiện tại");p.getMenu().add("Chuyển bảng vào nhóm");p.setOnMenuItemClickListener(i->{String s=i.getTitle().toString();if(s.startsWith("Tạo"))createGroupDialog();else if(s.startsWith("Đổi"))renameCurrent();else moveCurrentGroup();return true;});p.show();}
    void showGroupMenu(View anchor,GroupModel g){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("Đổi tên nhóm");p.getMenu().add("Xóa nhóm (giữ bảng)");p.setOnMenuItemClickListener(i->{if(i.getTitle().toString().startsWith("Đổi"))renameGroup(g);else deleteGroup(g);return true;});p.show();}

    void addCalcTable(boolean select){TableModel t=new TableModel();t.id=id();t.type="calc";t.title="Bảng "+(tables.size()+1);t.updated=System.currentTimeMillis();t.calcRows.add(new CalcRow());tables.add(t);if(select)selectedId=t.id;save();if(select)renderAll();}
    void addCancelTable(boolean select){TableModel t=new TableModel();t.id=id();t.type="cancel";t.title="Hủy vé";t.updated=System.currentTimeMillis();t.cancelRows.add(new CancelRow("",0));tables.add(t);if(select)selectedId=t.id;save();if(select)renderAll();}
    void deleteCurrent(){TableModel t=selected();if(t==null)return;new AlertDialog.Builder(this).setTitle("Xóa bảng?").setMessage(t.title).setPositiveButton("Xóa",(d,w)->{lastDeleted=t;lastDeletedIndex=tables.indexOf(t);tables.remove(t);selectedId=tables.isEmpty()?null:tables.get(Math.max(0,Math.min(lastDeletedIndex,tables.size()-1))).id;save();renderAll();}).setNegativeButton("Hủy",null).show();}
    void undoDelete(){if(lastDeleted==null)return;tables.add(Math.max(0,Math.min(lastDeletedIndex,tables.size())),lastDeleted);selectedId=lastDeleted.id;lastDeleted=null;lastDeletedIndex=-1;save();renderAll();}
    void renameCurrent(){TableModel t=selected();if(t==null)return;EditText e=new EditText(this);e.setText(t.title);e.setSingleLine();new AlertDialog.Builder(this).setTitle("Đổi tên bảng").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){t.title=s;t.updated=System.currentTimeMillis();save();renderAll();}}).setNegativeButton("Hủy",null).show();}
    void createGroupDialog(){EditText e=new EditText(this);e.setHint("Tên nhóm");new AlertDialog.Builder(this).setTitle("Tạo nhóm bảng").setView(padded(e)).setPositiveButton("Tạo",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){GroupModel g=new GroupModel();g.id=id();g.name=s;groups.add(g);save();renderAll();}}).setNegativeButton("Hủy",null).show();}
    void moveCurrentGroup(){TableModel t=selected();if(t==null)return;ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);new AlertDialog.Builder(this).setTitle("Chuyển vào nhóm").setItems(names.toArray(new String[0]),(d,i)->{t.groupId=i==0?UNGROUPED:groups.get(i-1).id;save();renderAll();}).show();}
    void renameGroup(GroupModel g){EditText e=new EditText(this);e.setText(g.name);new AlertDialog.Builder(this).setTitle("Đổi tên nhóm").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){g.name=s;save();renderAll();}}).setNegativeButton("Hủy",null).show();}
    void deleteGroup(GroupModel g){for(TableModel t:tables)if(g.id.equals(t.groupId))t.groupId=UNGROUPED;groups.remove(g);save();renderAll();}

    void editAgent(TableModel t,int row){activeRow=row;CancelRow r=t.cancelRows.get(row);EditText e=new EditText(this);e.setHint("Tên đại lý");e.setText(r.agent);e.setSingleLine();AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Tên đại lý").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{r.agent=e.getText().toString().trim();t.updated=System.currentTimeMillis();ensureBlankCancel(t);save();renderAll();}).setNegativeButton("Hủy",null).create();dlg.setOnShowListener(x->{e.requestFocus();dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dlg.show();}

    void ensureRow(TableModel t,int row){while(t.calcRows.size()<=row)t.calcRows.add(new CalcRow());}
    void ensureCancelRow(TableModel t,int row){while(t.cancelRows.size()<=row)t.cancelRows.add(new CancelRow("",0));}
    void ensureBlankCalc(TableModel t){if(t.calcRows.isEmpty()||!t.calcRows.get(t.calcRows.size()-1).blank())t.calcRows.add(new CalcRow());}
    void ensureBlankCancel(TableModel t){if(t.cancelRows.isEmpty()||!t.cancelRows.get(t.cancelRows.size()-1).blank())t.cancelRows.add(new CancelRow("",0));}

    void shareCurrent(){TableModel t=selected();if(t==null)return;try{LinearLayout report=buildShareView(t);int width=Math.min(dp(900),Math.max(dp(560),getResources().getDisplayMetrics().widthPixels));report.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));report.layout(0,0,width,report.getMeasuredHeight());Bitmap bmp=Bitmap.createBitmap(width,Math.max(1,report.getMeasuredHeight()),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bmp);c.drawColor(Color.WHITE);report.draw(c);File dir=new File(getCacheDir(),"share");dir.mkdirs();File f=new File(dir,"bang-"+System.currentTimeMillis()+".png");try(FileOutputStream os=new FileOutputStream(f)){bmp.compress(Bitmap.CompressFormat.PNG,100,os);}bmp.recycle();Uri uri=Uri.parse("content://com.vinh.listcalculatorfold2.share/"+Uri.encode(f.getName()));Intent send=new Intent(Intent.ACTION_SEND);send.setType("image/png");send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Chia sẻ ảnh"));}catch(Exception e){Toast.makeText(this,"Không tạo được ảnh",Toast.LENGTH_LONG).show();}}
    LinearLayout buildShareView(TableModel t){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(26),dp(22),dp(26),dp(24));r.setBackgroundColor(Color.WHITE);TextView h=text(t.title,23,true);h.setTextColor(Color.BLACK);r.addView(h);TextView dt=text(new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date()),12,false);dt.setTextColor(Color.GRAY);r.addView(dt);Space sp=new Space(this);r.addView(sp,new LinearLayout.LayoutParams(1,dp(12)));if("cancel".equals(t.type)){LinearLayout hh=shareRow();hh.addView(shareCell("Tên đại lý",true,Gravity.START),w(0,dp(42),3));hh.addView(shareCell("Số lượng",true,Gravity.END),w(0,dp(42),1));r.addView(hh);for(CancelRow x:t.cancelRows)if(!x.blank()){LinearLayout rr=shareRow();rr.addView(shareCell(x.agent,false,Gravity.START),w(0,dp(40),3));rr.addView(shareCell(String.valueOf(x.qty),false,Gravity.END),w(0,dp(40),1));r.addView(rr);}}else{LinearLayout hh=shareRow();hh.addView(shareCell("Đơn giá",true,Gravity.END),w(0,dp(42),2));hh.addView(shareCell("SL",true,Gravity.END),w(0,dp(42),1));hh.addView(shareCell("Thành tiền",true,Gravity.END),w(0,dp(42),2));r.addView(hh);for(CalcRow x:t.calcRows)if(!x.blank()){LinearLayout rr=shareRow();rr.addView(shareCell(plain(x.price),false,Gravity.END),w(0,dp(40),2));rr.addView(shareCell(plain(x.qty),false,Gravity.END),w(0,dp(40),1));rr.addView(shareCell(fmt(x.price*x.qty),false,Gravity.END),w(0,dp(40),2));r.addView(rr);}}TextView sum=text("TỔNG: "+fmt(t.total()),22,true);sum.setTextColor(navy);sum.setGravity(Gravity.END);sum.setPadding(0,dp(14),0,0);r.addView(sum);return r;}

    void save(){try{JSONObject root=new JSONObject();JSONArray ga=new JSONArray();for(GroupModel g:groups)ga.put(g.json());JSONArray ta=new JSONArray();for(TableModel t:tables)ta.put(t.json());root.put("groups",ga).put("tables",ta);getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(DATA,root.toString()).apply();}catch(Exception ignored){}}
    void load(){String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(DATA,null);if(s!=null){try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++)tables.add(TableModel.from(ta.getJSONObject(i)));return;}catch(Exception ignored){}}migrateOld();}
    void migrateOld(){String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(OLD_DATA,null);if(s==null)return;try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++){JSONObject x=ta.getJSONObject(i);TableModel t=new TableModel();t.id=x.optString("id",id());t.type=x.optString("type","calc");t.title=x.optString("title","Bảng");t.groupId=x.optString("groupId","");t.updated=System.currentTimeMillis();if("cancel".equals(t.type)){JSONArray c=x.optJSONArray("cancelRows");if(c!=null)for(int j=0;j<c.length();j++){JSONObject z=c.optJSONObject(j);t.cancelRows.add(new CancelRow(z.optString("agent"),z.optLong("qty")));}}else{JSONArray v=x.optJSONArray("values");if(v!=null)for(int j=0;j<v.length();j++){CalcRow cr=new CalcRow();cr.price=v.optDouble(j);cr.qty=1;t.calcRows.add(cr);}}tables.add(t);}save();}catch(Exception ignored){}}

    TableModel selected(){return findTable(selectedId);}TableModel findTable(String id){if(id==null)return null;for(TableModel t:tables)if(id.equals(t.id))return t;return null;}ArrayList<TableModel> inGroup(String gid){ArrayList<TableModel> a=new ArrayList<>();for(TableModel t:tables)if(gid.equals(t.groupId))a.add(t);return a;}double groupTotal(String gid){double x=0;for(TableModel t:tables)if(gid.equals(t.groupId))x+=t.total();return x;}

    static class GroupModel{String id,name;JSONObject json()throws Exception{return new JSONObject().put("id",id).put("name",name);}static GroupModel from(JSONObject o){GroupModel g=new GroupModel();g.id=o.optString("id");g.name=o.optString("name","Nhóm");return g;}}
    static class CalcRow{double price,qty;boolean blank(){return price==0&&qty==0;}JSONObject json()throws Exception{return new JSONObject().put("price",price).put("qty",qty);}static CalcRow from(JSONObject o){CalcRow r=new CalcRow();r.price=o.optDouble("price");r.qty=o.optDouble("qty");return r;}}
    static class CancelRow{String agent;long qty;CancelRow(String a,long q){agent=a;qty=q;}boolean blank(){return (agent==null||agent.trim().isEmpty())&&qty==0;}JSONObject json()throws Exception{return new JSONObject().put("agent",agent).put("qty",qty);}static CancelRow from(JSONObject o){return new CancelRow(o.optString("agent"),o.optLong("qty"));}}
    static class TableModel{String id,type="calc",title="Bảng",groupId="";long updated;ArrayList<CalcRow> calcRows=new ArrayList<>();ArrayList<CancelRow> cancelRows=new ArrayList<>();double total(){double x=0;if("cancel".equals(type)){for(CancelRow r:cancelRows)x+=r.qty;}else for(CalcRow r:calcRows)x+=r.price*r.qty;return x;}int dataRowCount(){int n=0;if("cancel".equals(type)){for(CancelRow r:cancelRows)if(!r.blank())n++;}else for(CalcRow r:calcRows)if(!r.blank())n++;return n;}JSONObject json()throws Exception{JSONObject o=new JSONObject().put("id",id).put("type",type).put("title",title).put("groupId",groupId).put("updated",updated);JSONArray a=new JSONArray();for(CalcRow r:calcRows)a.put(r.json());o.put("calcRows",a);JSONArray c=new JSONArray();for(CancelRow r:cancelRows)c.put(r.json());o.put("cancelRows",c);return o;}static TableModel from(JSONObject o){TableModel t=new TableModel();t.id=o.optString("id");t.type=o.optString("type","calc");t.title=o.optString("title","Bảng");t.groupId=o.optString("groupId","");t.updated=o.optLong("updated",System.currentTimeMillis());JSONArray a=o.optJSONArray("calcRows");if(a!=null)for(int i=0;i<a.length();i++)t.calcRows.add(CalcRow.from(a.optJSONObject(i)));JSONArray c=o.optJSONArray("cancelRows");if(c!=null)for(int i=0;i<c.length();i++)t.cancelRows.add(CancelRow.from(c.optJSONObject(i)));return t;}}

    LinearLayout gridRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setBackgroundColor(paper);return r;}TextView cell(String s,int sp,boolean bold,int gravity){TextView v=text(s,sp,bold);v.setGravity(gravity);v.setPadding(dp(10),0,dp(10),0);GradientDrawable d=new GradientDrawable();d.setColor(paper);d.setStroke(dp(1),rule);v.setBackground(d);return v;}LinearLayout shareRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}TextView shareCell(String s,boolean bold,int gravity){TextView v=text(s,14,bold);v.setGravity(gravity|Gravity.CENTER_VERTICAL);v.setPadding(dp(8),0,dp(8),0);GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setStroke(1,Color.LTGRAY);v.setBackground(d);return v;}
    Button topButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(navy);b.setAllCaps(false);b.setMinWidth(0);b.setMinHeight(0);GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(220,234,249));d.setCornerRadius(dp(10));b.setBackground(d);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(62),1);lp.setMargins(dp(3),0,dp(3),0);b.setLayoutParams(lp);return b;}Button keyButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(24);b.setTypeface(android.graphics.Typeface.DEFAULT,1);b.setTextColor("C".equals(s)?Color.rgb(175,38,38):navy);b.setAllCaps(false);b.setMinWidth(0);b.setMinHeight(0);GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(220,234,249));d.setCornerRadius(dp(10));b.setBackground(d);return b;}
    TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(ink);v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,1);v.setPadding(dp(4),dp(2),dp(4),dp(2));return v;}View padded(View v){LinearLayout l=new LinearLayout(this);l.setPadding(dp(20),0,dp(20),0);l.addView(v,new LinearLayout.LayoutParams(-1,-2));return l;}LinearLayout.LayoutParams w(int width,int height,float weight){return new LinearLayout.LayoutParams(width,height,weight);}void haptic(View v){v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}String id(){return UUID.randomUUID().toString();}double parseNum(String s){try{return s==null||s.isEmpty()?0:Double.parseDouble(s.replace(',','.'));}catch(Exception e){return 0;}}long parseLong(String s){try{return s==null||s.isEmpty()?0:Long.parseLong(s);}catch(Exception e){return 0;}}String plain(double n){return n==(long)n?String.valueOf((long)n):String.valueOf(n).replace('.',',');}String fmt(double n){NumberFormat f=NumberFormat.getNumberInstance(new Locale("vi","VN"));f.setMaximumFractionDigits(2);return f.format(n);}String timeText(long ts){if(ts<=0)ts=System.currentTimeMillis();return new SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new Date(ts));}
}
