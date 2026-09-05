package com.vinh.listcalculatorfold2;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.*;
import android.view.*;
import android.view.animation.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    static final String PREFS="list_calculator_fold_2", DATA="data_v2", OLD_DATA="data_v1", UNGROUPED="", FORMAT_KEY="number_format_mode";
    final ArrayList<GroupModel> groups=new ArrayList<>();
    final ArrayList<TableModel> tables=new ArrayList<>();
    LinearLayout sidebar, gridHost, keypadHost;
    ScrollView gridScroll;
    TextView pageIndicator, grandTotal;
    Button tableBtn, undoBtn, quick1000;
    String selectedId=null;
    int activeRow=0;
    int pendingScrollRow=-1;
    String activeField="price";
    TableModel lastDeleted=null; int lastDeletedIndex=-1;
    boolean compact=false;
    boolean explicitCellSelection=false;
    int numberFormatMode=0; // 0=1.000, 1=1,000, 2=1000
    float swipeDownX=0, swipeDownY=0;
    float globalDownX=0, globalDownY=0;
    boolean globalSwipeTracking=false;
    int lastWidthBucket=-1;
    final HashSet<String> collapsedGroups=new HashSet<>();
    int navy=Color.rgb(28,62,96), navy2=Color.rgb(48,92,136), pale=Color.rgb(225,236,248), paper=Color.rgb(255,250,226), ink=Color.rgb(42,42,42), rule=Color.rgb(218,208,172), red=Color.rgb(205,35,35);

    @Override public void onCreate(Bundle b){super.onCreate(b);numberFormatMode=getSharedPreferences(PREFS,MODE_PRIVATE).getInt(FORMAT_KEY,0);load();if(tables.isEmpty())addCalcTable(false);selectedId=tables.get(0).id;buildScreen();}


    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        int bucket=newConfig.screenWidthDp<600?0:1;
        if(bucket!=lastWidthBucket){
            // Giữ nguyên selectedId, activeRow, activeField và dữ liệu; chỉ dựng lại UI.
            buildScreen();
        }else{
            // Kích thước có thay đổi nhưng vẫn cùng chế độ: render lại để cột/phím lấy đúng kích thước mới.
            renderAll();
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if(tableBtn==null)return;
        int bucket=getResources().getConfiguration().screenWidthDp<600?0:1;
        if(bucket!=lastWidthBucket)buildScreen();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e){
        final int action=e.getActionMasked();

        if(action==MotionEvent.ACTION_DOWN){
            globalDownX=e.getRawX();
            globalDownY=e.getRawY();
            globalSwipeTracking=isSwipeZone(globalDownY);
        }else if(action==MotionEvent.ACTION_UP && globalSwipeTracking){
            float dx=e.getRawX()-globalDownX;
            float dy=e.getRawY()-globalDownY;
            globalSwipeTracking=false;

            // Vuốt ngang rõ ràng: ưu tiên chuyển bảng, không để ô con nhận ACTION_UP thành click.
            if(Math.abs(dx)>dp(72) && Math.abs(dx)>Math.abs(dy)*1.45f){
                if(dx<0)selectNextTable(); else selectPreviousTable();
                return true;
            }

            // Vuốt lên trong vùng bảng mở quản lý bảng/nhóm.
            if(dy<-dp(115) && Math.abs(dy)>Math.abs(dx)*1.45f){
                haptic(gridHost);
                showTableManagerSheet();
                return true;
            }
        }else if(action==MotionEvent.ACTION_CANCEL){
            globalSwipeTracking=false;
        }

        return super.dispatchTouchEvent(e);
    }

    boolean isSwipeZone(float rawY){
        int h=getResources().getDisplayMetrics().heightPixels;
        int topLimit=compact?dp(125):dp(78);
        int bottomLimit=h-(keypadHost==null?dp(300):keypadHost.getHeight());
        // Chỉ bắt gesture trong vùng bảng tính; không bắt trên thanh nút hoặc bàn phím số.
        return rawY>topLimit && rawY<bottomLimit;
    }

    void buildScreen(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(navy2);
        int swDp=getResources().getConfiguration().screenWidthDp;
        int shDp=getResources().getConfiguration().screenHeightDp;
        compact=swDp<600;
        lastWidthBucket=compact?0:1;
        LinearLayout top=new LinearLayout(this);top.setPadding(dp(8),dp(8),dp(8),dp(8));top.setBackgroundColor(navy);
        tableBtn=topButton("Bảng ("+tables.size()+")");tableBtn.setContentDescription("Danh sách bảng; có thể vuốt ngang vùng bảng để chuyển bảng"); Button del=topButton("Xóa bảng"); undoBtn=topButton("Hoàn tác"); Button share=topButton("↗"); quick1000=topButton("1.000"); Button add=topButton("+ Bảng");
        if(compact){
            top.setOrientation(LinearLayout.VERTICAL);
            LinearLayout tr1=new LinearLayout(this);tr1.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout tr2=new LinearLayout(this);tr2.setGravity(Gravity.CENTER_VERTICAL);
            tr1.addView(tableBtn,w(0,dp(54),1));tr1.addView(del,w(0,dp(54),1));tr1.addView(undoBtn,w(0,dp(54),1));
            tr2.addView(share,w(0,dp(54),1));tr2.addView(quick1000,w(0,dp(54),1));tr2.addView(add,w(0,dp(54),1));
            top.addView(tr1,new LinearLayout.LayoutParams(-1,dp(57)));
            top.addView(tr2,new LinearLayout.LayoutParams(-1,dp(57)));
        }else{
            top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(tableBtn,w(0,dp(62),1));top.addView(del,w(0,dp(62),1));top.addView(undoBtn,w(0,dp(62),1));top.addView(share,w(0,dp(62),1));top.addView(quick1000,w(0,dp(62),1));top.addView(add,w(0,dp(62),1));
        }
        root.addView(top);

        LinearLayout middle=new LinearLayout(this);middle.setOrientation(LinearLayout.HORIZONTAL);middle.setBackgroundColor(paper);
        ScrollView leftScroll=new ScrollView(this);leftScroll.setFillViewport(true);sidebar=new LinearLayout(this);sidebar.setOrientation(LinearLayout.VERTICAL);sidebar.setBackgroundColor(Color.rgb(235,243,252));leftScroll.addView(sidebar);
        int sideDp=swDp>=840?240:(swDp>=700?220:200);
        if(!compact) middle.addView(leftScroll,new LinearLayout.LayoutParams(dp(sideDp),-1));
        else sidebar=null;
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setBackgroundColor(paper);gridHost=new LinearLayout(this);gridHost.setOrientation(LinearLayout.VERTICAL);right.addView(gridHost,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(8),0,dp(10),0);pageIndicator=text("1/1",13,false);grandTotal=text("0",compact?20:24,true);grandTotal.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);footer.addView(pageIndicator,w(0,dp(compact?52:58),1));footer.addView(grandTotal,w(0,dp(58),3));right.addView(footer);
        middle.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        keypadHost=new LinearLayout(this);keypadHost.setOrientation(LinearLayout.HORIZONTAL);keypadHost.setPadding(dp(3),0,dp(3),dp(4));keypadHost.setBackgroundColor(navy2);int keypadDp=compact?Math.max(285,Math.min(330,(int)(shDp*0.32f))):310;
        root.addView(keypadHost,new LinearLayout.LayoutParams(-1,dp(keypadDp)));
        setContentView(root);

        add.setOnClickListener(v->{haptic(v);showAddMenu(add);}); del.setOnClickListener(v->{haptic(v);showMultiDeleteDialog();}); undoBtn.setOnClickListener(v->{haptic(v);undoDelete();}); share.setOnClickListener(v->{haptic(v);shareCurrent();}); quick1000.setOnClickListener(v->{haptic(v);cycleNumberFormat();}); tableBtn.setOnClickListener(v->{haptic(v);showTableManagerSheet();});
        renderAll();
    }

    void renderAll(){if(selected()==null&&!tables.isEmpty())selectedId=tables.get(0).id;tableBtn.setText("Bảng ("+tables.size()+")");quick1000.setText(formatSample());undoBtn.setEnabled(lastDeleted!=null);renderSidebar();renderGrid();renderKeypads();}

    void renderSidebar(){
        if(sidebar==null)return;
        sidebar.removeAllViews();
        addSidebarSection(UNGROUPED,null);
        for(GroupModel g:groups)addSidebarSection(g.id,g);
        sidebar.setOnDragListener((v,e)->true);
    }

    void addSidebarSection(String gid,GroupModel group){
        if(group!=null){
            LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);gh.setPadding(dp(9),dp(8),dp(7),dp(7));gh.setBackgroundColor(Color.rgb(205,223,242));
            TextView n=text((collapsedGroups.contains(gid)?"▸ ":"▾ ")+group.name,compact?12:14,true);TextView sum=text(fmt(groupTotal(gid)),compact?11:13,true);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);gh.addView(n,w(0,dp(40),1));gh.addView(sum,w(0,dp(40),0.9f));gh.setTag("GROUP:"+gid);
            gh.setOnDragListener((v,e)->{if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED){v.setAlpha(.65f);return true;}if(e.getAction()==DragEvent.ACTION_DRAG_EXITED){v.setAlpha(1f);return true;}if(e.getAction()==DragEvent.ACTION_DROP){v.setAlpha(1f);String id=(String)e.getLocalState();TableModel t=findTable(id);if(t!=null){tables.remove(t);t.groupId=gid;tables.add(t);save();renderAll();}return true;}if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)v.setAlpha(1f);return true;});
            gh.setOnClickListener(v->{haptic(v);if(collapsedGroups.contains(gid))collapsedGroups.remove(gid);else collapsedGroups.add(gid);renderSidebar();});
            gh.setOnLongClickListener(v->{haptic(v);renameGroup(group);return true;});
            installGroupQuickSwipe(gh,group);
            sidebar.addView(gh);
        }
        if(group!=null && collapsedGroups.contains(gid))return;
        ArrayList<TableModel> list=inGroup(gid); for(TableModel t:list)sidebar.addView(sidebarItem(t,gid));
    }

    View sidebarItem(TableModel t,String gid){
        LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.HORIZONTAL);item.setTag(t.id);item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(10),dp(7),dp(5),dp(7));item.setBackgroundColor(t.id.equals(selectedId)?Color.rgb(32,68,104):Color.TRANSPARENT);

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(4),0,dp(2),0);
        TextView title=text(t.title,compact?14:16,true);
        title.setTextColor(t.id.equals(selectedId)?Color.WHITE:navy);
        TextView meta=text(timeText(t.updated)+" · "+t.dataRowCount()+" dòng",compact?10:12,false);
        meta.setTextColor(t.id.equals(selectedId)?Color.rgb(220,232,245):Color.rgb(90,112,138));
        info.addView(title);info.addView(meta);

        TextView drag=text("≡",22,true);drag.setGravity(Gravity.CENTER);
        drag.setTextColor(t.id.equals(selectedId)?Color.WHITE:navy);
        item.addView(info,new LinearLayout.LayoutParams(0,dp(62),1));
        item.addView(drag,new LinearLayout.LayoutParams(dp(42),dp(62)));

        View.OnClickListener select=v->{selectedId=t.id;activeRow=0;activeField="cancel".equals(t.type)?"qty":"price";explicitCellSelection=false;renderAll();};
        info.setOnClickListener(select);
        title.setOnClickListener(select);

        // Nhấn giữ tên bảng = đổi tên, không còn xung đột với kéo thả.
        info.setOnLongClickListener(v->{haptic(v);selectedId=t.id;renameCurrent();return true;});

        // Chỉ kéo bằng tay cầm ≡.
        drag.setOnLongClickListener(v->{haptic(v);ClipData cd=ClipData.newPlainText("table",t.id);v.startDragAndDrop(cd,new View.DragShadowBuilder(item),t.id,0);return true;});

        installTableQuickSwipe(item,t);

        item.setOnDragListener((v,e)->{
            if(e.getAction()==DragEvent.ACTION_DROP){
                String movingId=(String)e.getLocalState();TableModel moving=findTable(movingId);
                if(moving==null||moving==t)return true;
                tables.remove(moving);moving.groupId=gid;int idx=tables.indexOf(t);
                tables.add(Math.max(0,idx),moving);save();renderAll();return true;
            }
            return true;
        });
        return item;
    }

    void renderGrid(){
        gridHost.removeAllViews();gridScroll=null;TableModel t=selected();if(t==null)return;
        if("cancel".equals(t.type))renderCancelGrid(t);else renderCalcGrid(t);
        pageIndicator.setText((tables.indexOf(t)+1)+"/"+tables.size());grandTotal.setText(fmt(t.total()));
        scrollActiveRowIntoView();
    }

    void renderCalcGrid(TableModel t){
        LinearLayout head=gridRow();head.addView(cell("STT",14,false,Gravity.CENTER),w(0,dp(compact?52:58),0.45f));head.addView(cell("Đơn giá",14,false,Gravity.CENTER),w(0,dp(compact?52:58),2));head.addView(cell("SL",14,false,Gravity.CENTER),w(0,dp(compact?52:58),1));head.addView(cell("Thành tiền",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,dp(compact?52:58),2));gridHost.addView(head);
        gridScroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);gridScroll.addView(body);gridHost.addView(gridScroll,new LinearLayout.LayoutParams(-1,0,1));
        ensureBlankCalc(t);int shown=Math.max(8,t.calcRows.size());for(int i=0;i<shown;i++){final int row=i;CalcRow r=i<t.calcRows.size()?t.calcRows.get(i):null;LinearLayout rr=gridRow();TextView st=cell(String.valueOf(i+1),14,false,Gravity.CENTER);st.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;explicitCellSelection=false;renderGrid();renderKeypads();});rr.addView(st,w(0,dp(compact?50:56),0.45f));TextView p=cell(r==null||r.price==0?"":fmt(r.price),16,false,Gravity.END|Gravity.CENTER_VERTICAL);TextView q=cell(r==null||r.qty==0?"":fmt(r.qty),16,false,Gravity.END|Gravity.CENTER_VERTICAL);TextView total=cell(r==null||r.price==0||r.qty==0?"":fmt(r.price*r.qty),16,false,Gravity.END|Gravity.CENTER_VERTICAL);if(row==activeRow&&"price".equals(activeField))markActive(p);if(row==activeRow&&"qty".equals(activeField))markActive(q);p.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;activeField="price";explicitCellSelection=true;ensureRow(t,row);renderGrid();renderKeypads();});q.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;activeField="qty";explicitCellSelection=true;ensureRow(t,row);renderGrid();renderKeypads();});rr.addView(p,w(0,dp(compact?50:56),2));rr.addView(q,w(0,dp(compact?50:56),1));rr.addView(total,w(0,dp(compact?50:56),2));
            rr.setOnLongClickListener(v->{haptic(v);showRowDeleteDialog(t,row);return true;});
            body.addView(rr);}
    }

    void renderCancelGrid(TableModel t){
        LinearLayout head=gridRow();head.addView(cell("STT",14,false,Gravity.CENTER),w(0,dp(compact?52:58),0.45f));head.addView(cell("Tên đại lý",14,false,Gravity.START|Gravity.CENTER_VERTICAL),w(0,dp(58),3));head.addView(cell("Số lượng",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,dp(compact?52:58),2));gridHost.addView(head);
        gridScroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);gridScroll.addView(body);gridHost.addView(gridScroll,new LinearLayout.LayoutParams(-1,0,1));ensureBlankCancel(t);int shown=Math.max(8,t.cancelRows.size());for(int i=0;i<shown;i++){final int row=i;CancelRow r=i<t.cancelRows.size()?t.cancelRows.get(i):null;LinearLayout rr=gridRow();rr.addView(cell(String.valueOf(i+1),14,false,Gravity.CENTER),w(0,dp(compact?50:56),0.45f));TextView a=cell(r==null?"":r.agent,16,false,Gravity.START|Gravity.CENTER_VERTICAL);TextView q=cell(r==null||r.qty==0?"":fmt(r.qty),16,false,Gravity.END|Gravity.CENTER_VERTICAL);if(row==activeRow)markActive(q);a.setOnClickListener(v->{ensureCancelRow(t,row);editAgent(t,row);});q.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;activeField="qty";explicitCellSelection=true;ensureCancelRow(t,row);renderGrid();renderKeypads();});rr.addView(a,w(0,dp(56),3));rr.addView(q,w(0,dp(compact?50:56),2));body.addView(rr);}
    }

    void renderKeypads(){keypadHost.removeAllViews();TableModel t=selected();if(t==null)return;if("cancel".equals(t.type)){LinearLayout filler=new LinearLayout(this);keypadHost.addView(filler,w(0,-1,1));keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));}else{keypadHost.addView(buildPad("Đơn giá","price"),w(0,-1,1));keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));}}

    LinearLayout buildPad(String label,String field){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);TextView lab=text(label,compact?14:15,field.equals(activeField));lab.setTextColor(field.equals(activeField)?Color.rgb(255,235,120):Color.WHITE);lab.setGravity(Gravity.CENTER);wrap.addView(lab,new LinearLayout.LayoutParams(-1,dp(28)));String[][] keys={{"7","8","9"},{"4","5","6"},{"1","2","3"},{"⌫","0","C"}};for(String[] row:keys){LinearLayout rr=new LinearLayout(this);rr.setPadding(0,0,dp(3),dp(3));for(String k:row){Button b=keyButton(k);b.setOnClickListener(v->{haptic(v);handleKey(field,k);});rr.addView(b,w(0,-1,1));}wrap.addView(rr,new LinearLayout.LayoutParams(-1,0,1));}return wrap;
    }

    void handleKey(String field,String key){
        TableModel t=selected();if(t==null)return;

        if("cancel".equals(t.type)){
            activeField="qty";
            ensureCancelRow(t,activeRow);
            CancelRow r=t.cancelRows.get(activeRow);
            String s=r.qty==0?"":String.valueOf(r.qty);
            s=editDigits(s,key);r.qty=parseLong(s);
            ensureBlankCancel(t);
        }else{
            ensureRow(t,activeRow);
            CalcRow current=t.calcRows.get(activeRow);

            // Luồng nhập kiểu List Calculator:
            // dòng đã đủ Đơn giá + SL, bấm phím Đơn giá tiếp theo => tự chuyển xuống dòng mới.
            if("price".equals(field) && !"C".equals(key) && !"⌫".equals(key)
                    && !explicitCellSelection && current.price!=0 && current.qty!=0){
                activeRow++;pendingScrollRow=activeRow;
                ensureRow(t,activeRow);
                current=t.calcRows.get(activeRow);
            }

            activeField=field;
            double cur="price".equals(field)?current.price:current.qty;
            String s=cur==0?"":plain(cur);
            s=editDigits(s,key);
            double val=parseNum(s);
            if("price".equals(field))current.price=val;else current.qty=val;

            // Khi bắt đầu nhập SL theo luồng bình thường, giữ nguyên dòng hiện tại.
            // Sau khi đã sửa trực tiếp một ô, lần bấm keypad tiếp theo trở lại chế độ nhập liên tục.
            explicitCellSelection=false;
            ensureBlankCalc(t);
        }

        t.updated=System.currentTimeMillis();
        save();renderGrid();renderSidebar();renderKeypads();
    }




    String editDigits(String s,String key){
        if(s==null)s="";
        if("C".equals(key))return "";
        if("⌫".equals(key))return s.length()>0?s.substring(0,s.length()-1):"";
        return s+key;
    }

    void cycleNumberFormat(){
        numberFormatMode=(numberFormatMode+1)%3;
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putInt(FORMAT_KEY,numberFormatMode).apply();
        quick1000.setText(formatSample());
        renderGrid();
        renderSidebar();
    }

    String formatSample(){
        if(numberFormatMode==1)return "1,000";
        if(numberFormatMode==2)return "1000";
        return "1.000";
    }

    void showRowDeleteDialog(TableModel t,int row){
        boolean hasData;
        if("cancel".equals(t.type)){
            hasData=row<t.cancelRows.size() && !t.cancelRows.get(row).blank();
        }else{
            hasData=row<t.calcRows.size() && !t.calcRows.get(row).blank();
        }
        if(!hasData)return;
        new AlertDialog.Builder(this)
            .setTitle("Dòng "+(row+1))
            .setItems(new String[]{"Xóa dòng"},(d,i)->{
                if("cancel".equals(t.type)){
                    if(row<t.cancelRows.size())t.cancelRows.remove(row);
                    ensureBlankCancel(t);
                }else{
                    if(row<t.calcRows.size())t.calcRows.remove(row);
                    ensureBlankCalc(t);
                }
                activeRow=Math.max(0,Math.min(activeRow,Math.max(0,t.dataRowCount()-1)));
                t.updated=System.currentTimeMillis();
                save();renderAll();
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

    void showMultiDeleteDialog(){
        if(tables.isEmpty())return;
        String[] names=new String[tables.size()];
        boolean[] checked=new boolean[tables.size()];
        for(int i=0;i<tables.size();i++){
            names[i]=tables.get(i).title;
            checked[i]=tables.get(i).id.equals(selectedId);
        }

        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle("Xóa nhanh nhiều bảng")
            .setMultiChoiceItems(names,checked,(d,i,on)->checked[i]=on)
            .setNeutralButton("Chọn tất cả",null)
            .setPositiveButton("Xóa đã chọn",null)
            .setNegativeButton("Hủy",null)
            .create();

        dlg.setOnShowListener(x->{
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                haptic(v);
                ListView list=dlg.getListView();
                for(int i=0;i<checked.length;i++){checked[i]=true;list.setItemChecked(i,true);}
            });
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                int count=0;for(boolean b:checked)if(b)count++;
                if(count==0){Toast.makeText(this,"Chưa chọn bảng nào",Toast.LENGTH_SHORT).show();return;}
                final int deleteCount=count;
                new AlertDialog.Builder(this)
                    .setTitle("Xóa "+deleteCount+" bảng?")
                    .setPositiveButton("Xóa nhanh",(d,w)->{
                        TableModel undo=null;int undoIndex=-1;
                        for(int i=tables.size()-1;i>=0;i--){
                            if(i<checked.length && checked[i]){
                                undo=tables.get(i);undoIndex=i;tables.remove(i);
                            }
                        }
                        lastDeleted=undo;lastDeletedIndex=undoIndex;
                        if(findTable(selectedId)==null)selectedId=tables.isEmpty()?null:tables.get(0).id;
                        save();dlg.dismiss();renderAll();
                    })
                    .setNegativeButton("Hủy",null).show();
            });
        });
        dlg.show();
    }

    void installTableSwipe(View target){
        target.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    swipeDownX=e.getX();swipeDownY=e.getY();return false;
                case MotionEvent.ACTION_UP:
                    float dx=e.getX()-swipeDownX,dy=e.getY()-swipeDownY;
                    if(Math.abs(dx)>dp(70) && Math.abs(dx)>Math.abs(dy)*1.35f){
                        if(dx<0)selectNextTable(); else selectPreviousTable();
                        return true;
                    }
                    if(dy<-dp(110) && Math.abs(dy)>Math.abs(dx)*1.35f){
                        haptic(v);showTableManagerSheet();return true;
                    }
                    return false;
            }
            return false;
        });
    }

    void selectNextTable(){
        if(tables.size()<2)return;
        TableModel cur=selected();int idx=cur==null?0:tables.indexOf(cur);
        idx=(idx+1)%tables.size();
        selectedId=tables.get(idx).id;activeRow=0;pendingScrollRow=0;
        activeField="cancel".equals(tables.get(idx).type)?"qty":"price";
        explicitCellSelection=false;haptic(gridHost);renderAll();
    }

    void selectPreviousTable(){
        if(tables.size()<2)return;
        TableModel cur=selected();int idx=cur==null?0:tables.indexOf(cur);
        idx=(idx-1+tables.size())%tables.size();
        selectedId=tables.get(idx).id;activeRow=0;pendingScrollRow=0;
        activeField="cancel".equals(tables.get(idx).type)?"qty":"price";
        explicitCellSelection=false;haptic(gridHost);renderAll();
    }


    void showAddMenu(View anchor){
        PopupMenu p=new PopupMenu(this,anchor);
        p.getMenu().add("Bảng tính");
        p.getMenu().add("Bảng hủy vé");
        p.setOnMenuItemClickListener(i->{
            String s=i.getTitle().toString();
            if(s.contains("hủy"))addCancelTable(true);
            else addCalcTable(true);
            return true;
        });
        p.show();
    }

    void showTableManagerSheet(){
        final Dialog dlg=new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10),dp(8),dp(10),dp(10));
        root.setBackgroundColor(Color.rgb(246,249,253));

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Quản lý bảng & nhóm",20,true);
        TextView hint=text("Vuốt trái: thao tác • Vuốt phải: chọn • Giữ ≡: kéo",11,false);
        hint.setTextColor(Color.GRAY);
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(title);titleBox.addView(hint);
        Button close=smallActionButton("Đóng");
        header.addView(titleBox,new LinearLayout.LayoutParams(0,dp(60),1));
        header.addView(close,new LinearLayout.LayoutParams(dp(76),dp(48)));
        root.addView(header);

        final HashSet<String> selectedIds=new HashSet<>();
        ScrollView sv=new ScrollView(this);
        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout bottom=new LinearLayout(this);
        bottom.setPadding(0,dp(6),0,0);
        Button newGroup=smallActionButton("+ Nhóm");
        Button move=smallActionButton("Chuyển");
        Button delete=smallActionButton("Xóa");
        Button selectAll=smallActionButton("Chọn hết");
        bottom.addView(newGroup,new LinearLayout.LayoutParams(0,dp(54),1));
        bottom.addView(move,new LinearLayout.LayoutParams(0,dp(54),1));
        bottom.addView(delete,new LinearLayout.LayoutParams(0,dp(54),1));
        bottom.addView(selectAll,new LinearLayout.LayoutParams(0,dp(54),1));
        root.addView(bottom);

        final Runnable[] rebuild=new Runnable[1];
        rebuild[0]=()->{
            list.removeAllViews();

            // Chưa nhóm
            TextView ungroupedHeader=managerSectionHeader("Chưa nhóm",fmt(groupTotal(UNGROUPED)),false);
            ungroupedHeader.setOnDragListener((v,e)->managerGroupDrop(e,UNGROUPED,dlg,rebuild[0]));
            list.addView(ungroupedHeader);
            for(TableModel t:new ArrayList<>(inGroup(UNGROUPED))){
                list.addView(managerTableRow(t,UNGROUPED,selectedIds,dlg,rebuild[0]));
            }

            for(GroupModel g:new ArrayList<>(groups)){
                LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);
                gh.setPadding(dp(8),dp(5),dp(4),dp(5));gh.setBackgroundColor(Color.rgb(216,230,246));
                TextView gname=text((collapsedGroups.contains(g.id)?"▸ ":"▾ ")+g.name,16,true);
                TextView sum=text(fmt(groupTotal(g.id)),14,true);sum.setTextColor(navy);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
                TextView more=text("⋮",24,true);more.setGravity(Gravity.CENTER);
                gh.addView(gname,new LinearLayout.LayoutParams(0,dp(50),1));
                gh.addView(sum,new LinearLayout.LayoutParams(dp(115),dp(50)));
                gh.addView(more,new LinearLayout.LayoutParams(dp(44),dp(50)));
                gh.setOnClickListener(v->{if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);rebuild[0].run();});
                gh.setOnLongClickListener(v->{haptic(v);dlg.dismiss();renameGroup(g);return true;});
                more.setOnClickListener(v->showManagerGroupActions(g,dlg,rebuild[0]));
                gh.setOnDragListener((v,e)->managerGroupDrop(e,g.id,dlg,rebuild[0]));
                installManagerGroupSwipe(gh,g,dlg,rebuild[0]);
                list.addView(gh);

                if(!collapsedGroups.contains(g.id)){
                    for(TableModel t:new ArrayList<>(inGroup(g.id))){
                        list.addView(managerTableRow(t,g.id,selectedIds,dlg,rebuild[0]));
                    }
                }
            }
        };
        rebuild[0].run();

        close.setOnClickListener(v->dlg.dismiss());
        newGroup.setOnClickListener(v->{dlg.dismiss();createGroupDialog();});
        selectAll.setOnClickListener(v->{
            if(selectedIds.size()==tables.size())selectedIds.clear();
            else {selectedIds.clear();for(TableModel t:tables)selectedIds.add(t.id);}
            haptic(v);rebuild[0].run();
        });
        move.setOnClickListener(v->{
            if(selectedIds.isEmpty()){Toast.makeText(this,"Vuốt phải hoặc chạm ô chọn để chọn bảng",Toast.LENGTH_SHORT).show();return;}
            showMoveSelectedDialog(selectedIds,dlg,rebuild[0]);
        });
        delete.setOnClickListener(v->{
            if(selectedIds.isEmpty()){Toast.makeText(this,"Chưa chọn bảng",Toast.LENGTH_SHORT).show();return;}
            confirmDeleteSelected(selectedIds,dlg);
        });

        dlg.setContentView(root);
        Window win=dlg.getWindow();
        if(win!=null){
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.setGravity(Gravity.BOTTOM);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams();
            lp.copyFrom(win.getAttributes());
            lp.width=WindowManager.LayoutParams.MATCH_PARENT;
            lp.height=(int)(getResources().getDisplayMetrics().heightPixels*(compact?0.88f:0.78f));
            win.setAttributes(lp);
        }
        dlg.show();
        // set again after show for some Android builds
        win=dlg.getWindow();
        if(win!=null){
            win.setGravity(Gravity.BOTTOM);
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT,(int)(getResources().getDisplayMetrics().heightPixels*(compact?0.88f:0.78f)));
        }
    }

    View managerTableRow(TableModel t,String gid,HashSet<String> selectedIds,Dialog dlg,Runnable rebuild){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8),dp(5),dp(4),dp(5));
        row.setBackgroundColor(t.id.equals(selectedId)?Color.rgb(232,241,252):Color.WHITE);

        CheckBox cb=new CheckBox(this);
        cb.setChecked(selectedIds.contains(t.id));
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(navy));

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(t.title,16,true);
        TextView meta=text(t.dataRowCount()+" dòng • "+fmt(t.total()),12,false);meta.setTextColor(Color.GRAY);
        info.addView(name);info.addView(meta);

        TextView drag=text("≡",24,true);drag.setGravity(Gravity.CENTER);drag.setTextColor(navy);
        TextView more=text("⋮",24,true);more.setGravity(Gravity.CENTER);

        row.addView(cb,new LinearLayout.LayoutParams(dp(48),dp(58)));
        row.addView(info,new LinearLayout.LayoutParams(0,dp(58),1));
        row.addView(drag,new LinearLayout.LayoutParams(dp(48),dp(58)));
        row.addView(more,new LinearLayout.LayoutParams(dp(44),dp(58)));

        cb.setOnCheckedChangeListener((b,on)->{if(on)selectedIds.add(t.id);else selectedIds.remove(t.id);});
        info.setOnClickListener(v->{selectedId=t.id;activeRow=0;activeField="cancel".equals(t.type)?"qty":"price";explicitCellSelection=false;dlg.dismiss();renderAll();});
        info.setOnLongClickListener(v->{selectedIds.add(t.id);cb.setChecked(true);haptic(v);return true;});

        drag.setOnLongClickListener(v->{
            haptic(v);
            ClipData cd=ClipData.newPlainText("table",t.id);
            v.startDragAndDrop(cd,new View.DragShadowBuilder(row),t.id,0);
            return true;
        });

        row.setOnDragListener((v,e)->{
            if(e.getAction()==DragEvent.ACTION_DROP){
                Object st=e.getLocalState();if(!(st instanceof String))return true;
                TableModel moving=findTable((String)st);if(moving==null||moving==t)return true;
                tables.remove(moving);moving.groupId=gid;int idx=tables.indexOf(t);tables.add(Math.max(0,idx),moving);
                save();rebuild.run();renderAll();return true;
            }
            return true;
        });

        more.setOnClickListener(v->showManagerTableActions(t,dlg,rebuild));
        installManagerTableSwipe(row,t,selectedIds,cb,dlg,rebuild);
        return row;
    }

    TextView managerSectionHeader(String name,String total,boolean group){
        TextView h=text(name+"     "+total,14,true);
        h.setPadding(dp(12),dp(7),dp(8),dp(7));
        h.setTextColor(navy);
        h.setBackgroundColor(Color.rgb(232,239,248));
        return h;
    }

    boolean managerGroupDrop(DragEvent e,String gid,Dialog dlg,Runnable rebuild){
        if(e.getAction()==DragEvent.ACTION_DROP){
            Object st=e.getLocalState();if(!(st instanceof String))return true;
            TableModel t=findTable((String)st);
            if(t!=null){tables.remove(t);t.groupId=gid;tables.add(t);save();rebuild.run();renderAll();}
            return true;
        }
        return true;
    }

    void installManagerTableSwipe(View row,TableModel t,HashSet<String> selectedIds,CheckBox cb,Dialog dlg,Runnable rebuild){
        final float[] down={0,0};
        row.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=e.getX();down[1]=e.getY();return false;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                if(Math.abs(dx)>dp(65)&&Math.abs(dx)>Math.abs(dy)*1.4f){
                    haptic(v);
                    if(dx<0)showManagerTableActions(t,dlg,rebuild);
                    else{
                        if(selectedIds.contains(t.id)){selectedIds.remove(t.id);cb.setChecked(false);}
                        else {selectedIds.add(t.id);cb.setChecked(true);}
                    }
                    return true;
                }
            }
            return false;
        });
    }

    void installManagerGroupSwipe(View row,GroupModel g,Dialog dlg,Runnable rebuild){
        final float[] down={0,0};
        row.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=e.getX();down[1]=e.getY();return false;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                if(dx<-dp(65)&&Math.abs(dx)>Math.abs(dy)*1.4f){haptic(v);showManagerGroupActions(g,dlg,rebuild);return true;}
            }
            return false;
        });
    }

    void showManagerTableActions(TableModel t,Dialog manager,Runnable rebuild){
        String[] actions={"Mở bảng","Đổi tên","Chuyển nhóm","Xóa bảng"};
        new AlertDialog.Builder(this).setTitle(t.title).setItems(actions,(d,i)->{
            if(i==0){selectedId=t.id;manager.dismiss();renderAll();}
            else if(i==1){selectedId=t.id;manager.dismiss();renameCurrent();}
            else if(i==2)showMoveOneDialog(t,manager,rebuild);
            else if(i==3)new AlertDialog.Builder(this).setTitle("Xóa "+t.title+"?")
                    .setPositiveButton("Xóa",(x,w)->{tables.remove(t);if(t.id.equals(selectedId))selectedId=tables.isEmpty()?null:tables.get(0).id;save();rebuild.run();renderAll();})
                    .setNegativeButton("Hủy",null).show();
        }).show();
    }

    void showManagerGroupActions(GroupModel g,Dialog manager,Runnable rebuild){
        String[] actions={"Đổi tên nhóm","Thu gọn / Mở rộng","Xóa nhóm (giữ bảng)"};
        new AlertDialog.Builder(this).setTitle(g.name).setItems(actions,(d,i)->{
            if(i==0){manager.dismiss();renameGroup(g);}
            else if(i==1){if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);rebuild.run();}
            else{
                for(TableModel t:tables)if(g.id.equals(t.groupId))t.groupId=UNGROUPED;
                groups.remove(g);save();rebuild.run();renderAll();
            }
        }).show();
    }

    void showMoveOneDialog(TableModel t,Dialog manager,Runnable rebuild){
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);
        new AlertDialog.Builder(this).setTitle("Chuyển "+t.title).setItems(names.toArray(new String[0]),(d,i)->{
            t.groupId=i==0?UNGROUPED:groups.get(i-1).id;
            tables.remove(t);tables.add(t);save();rebuild.run();renderAll();
        }).show();
    }

    void showMoveSelectedDialog(HashSet<String> selectedIds,Dialog manager,Runnable rebuild){
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);
        new AlertDialog.Builder(this).setTitle("Chuyển "+selectedIds.size()+" bảng").setItems(names.toArray(new String[0]),(d,i)->{
            String gid=i==0?UNGROUPED:groups.get(i-1).id;
            ArrayList<TableModel> moving=new ArrayList<>();
            for(TableModel t:new ArrayList<>(tables))if(selectedIds.contains(t.id)){tables.remove(t);t.groupId=gid;moving.add(t);}
            tables.addAll(moving);save();rebuild.run();renderAll();
        }).show();
    }

    void confirmDeleteSelected(HashSet<String> selectedIds,Dialog manager){
        int n=selectedIds.size();
        new AlertDialog.Builder(this).setTitle("Xóa "+n+" bảng?")
            .setMessage("Các bảng đã chọn sẽ bị xóa.")
            .setPositiveButton("Xóa",(d,w)->{
                TableModel undo=null;int undoIndex=-1;
                for(int i=tables.size()-1;i>=0;i--)if(selectedIds.contains(tables.get(i).id)){undo=tables.get(i);undoIndex=i;tables.remove(i);}
                lastDeleted=undo;lastDeletedIndex=undoIndex;
                if(findTable(selectedId)==null)selectedId=tables.isEmpty()?null:tables.get(0).id;
                save();manager.dismiss();renderAll();
            })
            .setNegativeButton("Hủy",null).show();
    }

    Button smallActionButton(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(0);b.setMinWidth(0);
        GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(222,235,249));d.setCornerRadius(dp(10));b.setBackground(d);
        return b;
    }

    void installTableQuickSwipe(View row,TableModel t){
        final float[] down={0,0};
        row.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=e.getX();down[1]=e.getY();return false;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                if(dx<-dp(55)&&Math.abs(dx)>Math.abs(dy)*1.4f){haptic(v);showQuickTableActions(t);return true;}
            }
            return false;
        });
    }

    void showQuickTableActions(TableModel t){
        String[] a={"Đổi tên","Chuyển nhóm","Xóa"};
        new AlertDialog.Builder(this).setTitle(t.title).setItems(a,(d,i)->{
            selectedId=t.id;
            if(i==0)renameCurrent();
            else if(i==1)moveCurrentGroup();
            else deleteCurrent();
        }).show();
    }

    void installGroupQuickSwipe(View row,GroupModel g){
        final float[] down={0,0};
        row.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=e.getX();down[1]=e.getY();return false;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                if(dx<-dp(55)&&Math.abs(dx)>Math.abs(dy)*1.4f){haptic(v);showGroupMenu(v,g);return true;}
            }
            return false;
        });
    }

    void showTableMenu(View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("Tạo nhóm");p.getMenu().add("Đổi tên bảng hiện tại");p.getMenu().add("Chuyển bảng vào nhóm");p.setOnMenuItemClickListener(i->{String s=i.getTitle().toString();if(s.startsWith("Tạo"))createGroupDialog();else if(s.startsWith("Đổi"))renameCurrent();else moveCurrentGroup();return true;});p.show();}
    void showGroupMenu(View anchor,GroupModel g){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("Đổi tên nhóm");p.getMenu().add("Xóa nhóm (giữ bảng)");p.setOnMenuItemClickListener(i->{if(i.getTitle().toString().startsWith("Đổi"))renameGroup(g);else deleteGroup(g);return true;});p.show();}

    void addCalcTable(boolean select){TableModel t=new TableModel();t.id=id();t.type="calc";t.title="Bảng "+(tables.size()+1);t.updated=System.currentTimeMillis();t.calcRows.add(new CalcRow());tables.add(t);if(select)selectedId=t.id;save();if(select)renderAll();}
    void addCancelTable(boolean select){TableModel t=new TableModel();t.id=id();t.type="cancel";t.title="Hủy vé";t.updated=System.currentTimeMillis();t.cancelRows.add(new CancelRow("",0));tables.add(t);if(select)selectedId=t.id;save();if(select)renderAll();}
    void deleteCurrent(){TableModel t=selected();if(t==null)return;new AlertDialog.Builder(this).setTitle("Xóa bảng?").setMessage(t.title).setPositiveButton("Xóa",(d,w)->{lastDeleted=t;lastDeletedIndex=tables.indexOf(t);tables.remove(t);selectedId=tables.isEmpty()?null:tables.get(Math.max(0,Math.min(lastDeletedIndex,tables.size()-1))).id;save();renderAll();}).setNegativeButton("Hủy",null).show();}
    void undoDelete(){if(lastDeleted==null)return;tables.add(Math.max(0,Math.min(lastDeletedIndex,tables.size())),lastDeleted);selectedId=lastDeleted.id;lastDeleted=null;lastDeletedIndex=-1;save();renderAll();}
    void renameCurrent(){TableModel t=selected();if(t==null)return;EditText e=new EditText(this);e.setText(t.title);e.setSelectAllOnFocus(true);e.setSingleLine();AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Đổi tên bảng").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){t.title=s;t.updated=System.currentTimeMillis();save();renderAll();}}).setNegativeButton("Hủy",null).create();dlg.setOnShowListener(x->{e.requestFocus();e.selectAll();dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dlg.show();}
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

    void markActive(TextView v){
        GradientDrawable d=new GradientDrawable();
        d.setColor(Color.rgb(255,247,205));
        d.setStroke(dp(2),red);
        v.setBackground(d);
        v.setTextColor(red);
        AlphaAnimation a=new AlphaAnimation(1f,.45f);
        a.setDuration(420);
        a.setRepeatMode(Animation.REVERSE);
        a.setRepeatCount(Animation.INFINITE);
        v.startAnimation(a);
    }


    void scrollActiveRowIntoView(){
        if(gridScroll==null)return;
        final int row=pendingScrollRow>=0?pendingScrollRow:activeRow;
        gridScroll.post(()->{
            View child=gridScroll.getChildAt(0);
            if(!(child instanceof LinearLayout))return;
            LinearLayout body=(LinearLayout)child;
            if(body.getChildCount()==0)return;

            int idx=Math.max(0,Math.min(row,body.getChildCount()-1));
            View target=body.getChildAt(idx);

            // Đưa dòng đang nhập xuống gần đáy vùng bảng nhưng vẫn chừa khoảng nhìn dòng kế/tổng.
            int desired=gridScroll.getHeight()-target.getHeight()-dp(18);
            int y=Math.max(0,target.getTop()-Math.max(dp(12),desired));
            gridScroll.smoothScrollTo(0,y);
            pendingScrollRow=-1;
        });
    }

    LinearLayout gridRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setBackgroundColor(paper);return r;}TextView cell(String s,int sp,boolean bold,int gravity){if(compact)sp=Math.max(11,sp-2);TextView v=text(s,sp,bold);v.setGravity(gravity);v.setPadding(dp(10),0,dp(10),0);GradientDrawable d=new GradientDrawable();d.setColor(paper);d.setStroke(dp(1),rule);v.setBackground(d);return v;}LinearLayout shareRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}TextView shareCell(String s,boolean bold,int gravity){TextView v=text(s,14,bold);v.setGravity(gravity|Gravity.CENTER_VERTICAL);v.setPadding(dp(8),0,dp(8),0);GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setStroke(1,Color.LTGRAY);v.setBackground(d);return v;}
    Button topButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(compact?12:14);b.setTextColor(navy);b.setAllCaps(false);b.setMinWidth(0);b.setMinHeight(0);GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(220,234,249));d.setCornerRadius(dp(10));b.setBackground(d);b.setPadding(dp(4),0,dp(4),0);return b;}Button keyButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(compact?23:25);b.setTypeface(android.graphics.Typeface.DEFAULT,1);b.setTextColor("C".equals(s)?Color.rgb(175,38,38):navy);b.setAllCaps(false);b.setMinWidth(0);b.setMinHeight(0);GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(220,234,249));d.setCornerRadius(dp(10));b.setBackground(d);return b;}
    TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(ink);v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT,1);v.setPadding(dp(4),dp(2),dp(4),dp(2));return v;}View padded(View v){LinearLayout l=new LinearLayout(this);l.setPadding(dp(20),0,dp(20),0);l.addView(v,new LinearLayout.LayoutParams(-1,-2));return l;}LinearLayout.LayoutParams w(int width,int height,float weight){return new LinearLayout.LayoutParams(width,height,weight);}void haptic(View v){v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}String id(){return UUID.randomUUID().toString();}double parseNum(String s){try{return s==null||s.isEmpty()?0:Double.parseDouble(s.replace(',','.'));}catch(Exception e){return 0;}}long parseLong(String s){try{return s==null||s.isEmpty()?0:Long.parseLong(s);}catch(Exception e){return 0;}}String plain(double n){return n==(long)n?String.valueOf((long)n):String.valueOf(n).replace('.',',');}String fmt(double n){
        NumberFormat f;
        if(numberFormatMode==1) f=NumberFormat.getNumberInstance(Locale.US);
        else if(numberFormatMode==2){
            f=new DecimalFormat("0.##");
            f.setGroupingUsed(false);
        }else f=NumberFormat.getNumberInstance(new Locale("vi","VN"));
        f.setMaximumFractionDigits(2);
        f.setMinimumFractionDigits(0);
        return f.format(n);
    }String timeText(long ts){if(ts<=0)ts=System.currentTimeMillis();return new SimpleDateFormat("dd/MM HH:mm",Locale.getDefault()).format(new Date(ts));}
}
