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
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.io.*;
import java.text.*;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    static final String PREFS="list_calculator_fold_2", DATA="data_v2", OLD_DATA="data_v1", UNGROUPED="",
        FORMAT_KEY="number_format_mode", SELECTED_KEY="selected_table_id", ACTIVE_ROW_KEY="active_row",
        ACTIVE_FIELD_KEY="active_field", COLLAPSED_KEY="collapsed_groups", FAST_INPUT_KEY="fast_input_mode",
        SHARE_BLANK_KEY="share_hide_blank";
    static final int REQ_BACKUP=501, REQ_RESTORE=502;
    final ArrayList<GroupModel> groups=new ArrayList<>();
    final ArrayList<TableModel> tables=new ArrayList<>();
    LinearLayout sidebar, gridHost, keypadHost;
    ScrollView gridScroll;
    RecyclerView gridRecycler;
    TextView pageIndicator, grandTotal, compactTableTitle, compactGroupTitle;
    Button clearQtyBtn;
    View topInsetSpacer;
    Button tableBtn, undoBtn, quick1000;
    String selectedId=null;
    int activeRow=0;
    int pendingScrollRow=-1;
    String activeField="price";
    TableModel lastDeleted=null; int lastDeletedIndex=-1;
    String undoSnapshot=null, undoLabel="";
    int fastInputMode=0; // 0=List Calculator, 1=Enter/Tab tự chuyển ô, 2=thủ công
    boolean shareHideBlank=true;
    int managerSortMode=0; // 0=thủ công,1=tên,2=mới nhất,3=cũ nhất
    boolean compact=false;
    boolean compactLandscape=false;
    boolean explicitCellSelection=false;
    int numberFormatMode=0; // 0=1.000, 1=1,000, 2=1000
    float swipeDownX=0, swipeDownY=0;
    float globalDownX=0, globalDownY=0;
    boolean globalSwipeTracking=false;
    boolean globalDragging=false;
    boolean tableSwipeAnimating=false;
    int lastWidthBucket=-1;
    final HashSet<String> collapsedGroups=new HashSet<>();
    int navy=Color.rgb(27,67,110),
        navy2=Color.rgb(239,245,251),
        pale=Color.rgb(235,243,252),
        paper=Color.rgb(252,253,255),
        ink=Color.rgb(30,41,59),
        rule=Color.rgb(226,232,240),
        red=Color.rgb(220,38,38);
    int accent=Color.rgb(37,99,235),
        accentSoft=Color.rgb(239,246,255),
        selectedBg=Color.rgb(224,237,255),
        groupBg=Color.rgb(241,245,249),
        keypadBg=Color.rgb(248,250,252),
        muted=Color.rgb(100,116,139);

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        if(android.os.Build.VERSION.SDK_INT>=21){
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
        }
        if(android.os.Build.VERSION.SDK_INT>=23){
            getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        android.content.SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        numberFormatMode=p.getInt(FORMAT_KEY,0);
        fastInputMode=p.getInt(FAST_INPUT_KEY,0);
        shareHideBlank=p.getBoolean(SHARE_BLANK_KEY,true);
        load();
        if(tables.isEmpty())addCalcTable(false);
        String savedSelected=p.getString(SELECTED_KEY,null);
        selectedId=findTable(savedSelected)!=null?savedSelected:tables.get(0).id;
        activeRow=Math.max(0,p.getInt(ACTIVE_ROW_KEY,0));
        activeField=p.getString(ACTIVE_FIELD_KEY,"price");
        String collapsed=p.getString(COLLAPSED_KEY,"");
        if(collapsed!=null&&!collapsed.isEmpty()){
            for(String s:collapsed.split("\\|"))if(!s.isEmpty())collapsedGroups.add(s);
        }
        buildScreen();
    }


    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        // Z Fold6: xoay màn ngoài làm thay đổi chiều cao rất lớn.
        // Luôn dựng lại layout để toolbar, vùng bảng và keypad nhận kích thước mới.
        buildScreen();
    }

    @Override protected void onResume(){
        super.onResume();
        if(tableBtn==null)return;
        android.content.res.Configuration c=getResources().getConfiguration();
        int bucket=c.screenWidthDp<600?0:1;
        boolean land=(bucket==0 && c.screenWidthDp>c.screenHeightDp);
        if(bucket!=lastWidthBucket || land!=compactLandscape)buildScreen();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e){
        final int action=e.getActionMasked();

        if(tableSwipeAnimating)return super.dispatchTouchEvent(e);

        if(action==MotionEvent.ACTION_DOWN){
            globalDownX=e.getRawX();
            globalDownY=e.getRawY();
            globalSwipeTracking=isSwipeZone(globalDownY);
            globalDragging=false;
        }else if(action==MotionEvent.ACTION_MOVE && globalSwipeTracking){
            float dx=e.getRawX()-globalDownX;
            float dy=e.getRawY()-globalDownY;

            // Chỉ bắt khi ý định vuốt ngang đã rõ ràng.
            if(!globalDragging && Math.abs(dx)>dp(10) && Math.abs(dx)>Math.abs(dy)*1.25f){
                globalDragging=true;
            }

            if(globalDragging && gridHost!=null){
                // Cho bảng đi theo tay nhưng có chút "resistance" để tự nhiên hơn.
                float drag=dx*0.78f;

                // Ở đầu/cuối danh sách cho cảm giác đàn hồi thay vì trượt vô hạn.
                TableModel cur=selected();
                int idx=cur==null?0:tables.indexOf(cur);
                boolean atFirst=idx<=0;
                boolean atLast=idx>=tables.size()-1;
                if((dx>0 && atFirst)||(dx<0 && atLast))drag=dx*0.28f;

                gridHost.setTranslationX(drag);
                gridHost.setAlpha(1f-Math.min(0.10f,Math.abs(drag)/(getResources().getDisplayMetrics().widthPixels*5f)));
                return true;
            }
        }else if(action==MotionEvent.ACTION_UP && globalSwipeTracking){
            float dx=e.getRawX()-globalDownX;
            float dy=e.getRawY()-globalDownY;
            boolean wasDragging=globalDragging;
            globalSwipeTracking=false;
            globalDragging=false;

            if(wasDragging && gridHost!=null){
                int threshold=Math.max(dp(55),(int)(getResources().getDisplayMetrics().widthPixels*0.14f));

                TableModel cur=selected();
                int idx=cur==null?0:tables.indexOf(cur);
                boolean canPrev=idx>0;
                boolean canNext=idx<tables.size()-1;

                if(Math.abs(dx)>threshold && Math.abs(dx)>Math.abs(dy)*1.25f){
                    if(dx<0 && canNext){
                        animateTablePageChange(true);
                    }else if(dx>0 && canPrev){
                        animateTablePageChange(false);
                    }else{
                        springTableBack();
                    }
                }else{
                    springTableBack();
                }
                return true;
            }
        }else if(action==MotionEvent.ACTION_CANCEL){
            globalSwipeTracking=false;
            globalDragging=false;
            springTableBack();
        }

        return super.dispatchTouchEvent(e);
    }


    void springTableBack(){
        if(gridHost==null)return;
        gridHost.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(150)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
            .start();
    }

    void animateTablePageChange(boolean next){
        if(gridHost==null||tables.size()<2)return;
        tableSwipeAnimating=true;

        final int width=Math.max(gridHost.getWidth(),getResources().getDisplayMetrics().widthPixels);
        final float outX=next?-width*0.34f:width*0.34f;

        gridHost.animate()
            .translationX(outX)
            .alpha(0.72f)
            .setDuration(105)
            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.35f))
            .withEndAction(()->{
                // Đổi bảng nhưng tránh dùng hàm cũ để không rung/animation trùng.
                TableModel cur=selected();
                int idx=cur==null?0:tables.indexOf(cur);
                idx=next?Math.min(tables.size()-1,idx+1):Math.max(0,idx-1);

                selectedId=tables.get(idx).id;
                activeRow=0;
                pendingScrollRow=0;
                activeField="cancel".equals(tables.get(idx).type)?"qty":"price";
                explicitCellSelection=false;saveUiState();

                // Dựng nội dung bảng mới.
                renderAll();

                if(gridHost!=null){
                    gridHost.setTranslationX(next?width*0.22f:-width*0.22f);
                    gridHost.setAlpha(0.78f);
                    gridHost.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(165)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f))
                        .withEndAction(()->{
                            tableSwipeAnimating=false;
                            if(gridHost!=null)haptic(gridHost);
                        })
                        .start();
                }else{
                    tableSwipeAnimating=false;
                }
            })
            .start();
    }

    boolean isSwipeZone(float rawY){
        int h=getResources().getDisplayMetrics().heightPixels;
        int topLimit=compact?dp(125):dp(78);
        int bottomLimit=h-(keypadHost==null?dp(300):keypadHost.getHeight());
        // Chỉ bắt gesture trong vùng bảng tính; không bắt trên thanh nút hoặc bàn phím số.
        return rawY>topLimit && rawY<bottomLimit;
    }


    void applyTopSystemInset(){
        applyTopSystemInset(topInsetSpacer);
    }

    void applyTopSystemInset(View spacer){
        if(spacer==null)return;
        if(android.os.Build.VERSION.SDK_INT>=23){
            spacer.setOnApplyWindowInsetsListener((v,insets)->{
                int top=insets.getSystemWindowInsetTop();
                ViewGroup.LayoutParams lp=v.getLayoutParams();
                lp.height=top;
                v.setLayoutParams(lp);
                return insets;
            });
            spacer.requestApplyInsets();
        }else{
            int res=getResources().getIdentifier("status_bar_height","dimen","android");
            int top=res>0?getResources().getDimensionPixelSize(res):0;
            ViewGroup.LayoutParams lp=spacer.getLayoutParams();
            lp.height=top;
            spacer.setLayoutParams(lp);
        }
    }

    void buildScreen(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(248,250,252));
        View sheetInsetSpacer=new View(this);
        sheetInsetSpacer.setBackgroundColor(Color.WHITE);
        root.addView(sheetInsetSpacer,new LinearLayout.LayoutParams(-1,0));
        applyTopSystemInset(sheetInsetSpacer);
        int swDp=getResources().getConfiguration().screenWidthDp;
        int shDp=getResources().getConfiguration().screenHeightDp;
        compact=swDp<600;
        compactLandscape=compact && swDp>shDp;
        lastWidthBucket=compact?0:1;
        LinearLayout top=new LinearLayout(this);
        top.setOrientation(compact && !compactLandscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8),dp(5),dp(8),dp(5));
        top.setBackgroundColor(Color.WHITE);
        top.setElevation(dp(2));

        tableBtn=topButton("☰  Bảng ("+tables.size()+")");
        Button del=topButton("⌫  Xóa");
        undoBtn=topButton("↶  Hoàn tác");
        Button share=topButton("↗  Chia sẻ");
        quick1000=topButton(formatSample());
        Button addCalc=topButton("+  Bảng tính");
        Button addCancel=topButton("+  Bảng hủy");

        tableBtn.setOnClickListener(v->{haptic(v);showTableManagerSheet();});
        del.setOnClickListener(v->{haptic(v);showMultiDeleteDialog();});
        undoBtn.setOnClickListener(v->{haptic(v);undoDelete();});
        share.setOnClickListener(v->{haptic(v);shareCurrent();});
        quick1000.setOnClickListener(v->{haptic(v);cycleNumberFormat();});
        addCalc.setOnClickListener(v->{haptic(v);addCalcTable(true);});
        addCancel.setOnClickListener(v->{haptic(v);addCancelTable(true);});

        if(compact && !compactLandscape){
            LinearLayout r1=new LinearLayout(this);r1.setGravity(Gravity.CENTER);
            LinearLayout r2=new LinearLayout(this);r2.setGravity(Gravity.CENTER);
            r1.addView(tableBtn,new LinearLayout.LayoutParams(0,dp(40),1.15f));
            r1.addView(del,new LinearLayout.LayoutParams(0,dp(40),1f));
            r1.addView(undoBtn,new LinearLayout.LayoutParams(0,dp(40),1.1f));
            r1.addView(share,new LinearLayout.LayoutParams(0,dp(40),1f));
            r2.addView(quick1000,new LinearLayout.LayoutParams(0,dp(40),.9f));
            r2.addView(addCalc,new LinearLayout.LayoutParams(0,dp(40),1.25f));
            r2.addView(addCancel,new LinearLayout.LayoutParams(0,dp(40),1.35f));
            top.addView(r1,new LinearLayout.LayoutParams(-1,dp(42)));
            top.addView(r2,new LinearLayout.LayoutParams(-1,dp(42)));
        }else{
            int h=dp(compactLandscape?38:44);
            top.addView(tableBtn,new LinearLayout.LayoutParams(0,h,1.12f));
            top.addView(del,new LinearLayout.LayoutParams(0,h,.92f));
            top.addView(undoBtn,new LinearLayout.LayoutParams(0,h,1.02f));
            top.addView(share,new LinearLayout.LayoutParams(0,h,.95f));
            top.addView(quick1000,new LinearLayout.LayoutParams(0,h,.78f));
            top.addView(addCalc,new LinearLayout.LayoutParams(0,h,1.18f));
            top.addView(addCancel,new LinearLayout.LayoutParams(0,h,1.24f));
        }

        root.addView(top,new LinearLayout.LayoutParams(
                -1,
                compactLandscape?dp(44):(compact?dp(94):dp(52))
        ));

        if(compact){
            LinearLayout currentBar=new LinearLayout(this);
            currentBar.setGravity(Gravity.CENTER_VERTICAL);
            currentBar.setPadding(dp(8),dp(4),dp(8),dp(4));
            GradientDrawable cbg=new GradientDrawable();
            cbg.setColor(Color.WHITE);
            cbg.setStroke(dp(1),Color.rgb(226,232,240));
            cbg.setCornerRadius(dp(14));
            currentBar.setBackground(cbg);
            currentBar.setElevation(dp(1));

            Button prev=smallActionButton("‹");
            Button next=smallActionButton("›");

            LinearLayout labels=new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER);

            compactTableTitle=text("",16,true);compactTableTitle.setTextColor(ink);
            compactTableTitle.setGravity(Gravity.CENTER);
            compactGroupTitle=text("",11,false);
            compactGroupTitle.setTextColor(muted);
            compactGroupTitle.setGravity(Gravity.CENTER);

            if(compactLandscape){
                labels.addView(compactTableTitle,new LinearLayout.LayoutParams(-1,dp(32)));
                compactGroupTitle.setVisibility(View.GONE);
            }else{
                labels.addView(compactTableTitle,new LinearLayout.LayoutParams(-1,dp(28)));
                labels.addView(compactGroupTitle,new LinearLayout.LayoutParams(-1,dp(20)));
            }

            int navH=dp(compactLandscape?36:48);
            currentBar.addView(prev,new LinearLayout.LayoutParams(dp(compactLandscape?46:52),navH));
            currentBar.addView(labels,new LinearLayout.LayoutParams(0,navH,1));
            currentBar.addView(next,new LinearLayout.LayoutParams(dp(compactLandscape?46:52),navH));

            prev.setOnClickListener(v->{haptic(v);animateTablePageChange(false);});
            next.setOnClickListener(v->{haptic(v);animateTablePageChange(true);});
            labels.setOnClickListener(v->{haptic(v);showTableManagerSheet();});

            root.addView(currentBar,new LinearLayout.LayoutParams(-1,dp(compactLandscape?36:46)));
        }else{
            compactTableTitle=null;
            compactGroupTitle=null;
        }

        LinearLayout middle=new LinearLayout(this);middle.setOrientation(LinearLayout.HORIZONTAL);middle.setBackgroundColor(Color.rgb(248,250,252));middle.setPadding(0,dp(2),0,0);
        ScrollView leftScroll=new ScrollView(this);leftScroll.setFillViewport(true);sidebar=new LinearLayout(this);sidebar.setOrientation(LinearLayout.VERTICAL);sidebar.setBackgroundColor(Color.WHITE);leftScroll.addView(sidebar);
        int sideDp=swDp>=840?240:(swDp>=700?220:200);
        if(!compact) middle.addView(leftScroll,new LinearLayout.LayoutParams(dp(sideDp),-1));
        else sidebar=null;
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setBackgroundColor(Color.WHITE);gridHost=new LinearLayout(this);gridHost.setOrientation(LinearLayout.VERTICAL);gridHost.setBackgroundColor(Color.WHITE);gridHost.setElevation(dp(1));right.addView(gridHost,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(8),0,dp(10),0);footer.setBackgroundColor(Color.rgb(248,250,252));
        pageIndicator=text("1/1",13,false);
        grandTotal=text("0",compact?21:25,true);grandTotal.setTextColor(accent);grandTotal.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        int footerH=compactLandscape?40:(compact?52:58);

        LinearLayout footerLeft=new LinearLayout(this);
        footerLeft.setGravity(Gravity.CENTER_VERTICAL);
        footerLeft.addView(pageIndicator,new LinearLayout.LayoutParams(0,dp(footerH),1));

        clearQtyBtn=smallActionButton("Xóa SL");
        clearQtyBtn.setTextColor(red);
        clearQtyBtn.setVisibility(View.GONE);
        clearQtyBtn.setOnClickListener(v->{haptic(v);confirmClearCurrentQuantities();});
        footerLeft.addView(clearQtyBtn,new LinearLayout.LayoutParams(dp(compactLandscape?72:86),dp(compactLandscape?32:38)));

        footer.addView(footerLeft,new LinearLayout.LayoutParams(0,dp(footerH),1.45f));
        footer.addView(grandTotal,new LinearLayout.LayoutParams(0,dp(footerH),2.55f));
        right.addView(footer);
        middle.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        keypadHost=new LinearLayout(this);keypadHost.setOrientation(LinearLayout.HORIZONTAL);keypadHost.setPadding(dp(3),0,dp(3),dp(4));keypadHost.setBackgroundColor(Color.rgb(241,245,249));int keypadDp;
        if(compactLandscape){
            // 4 hàng phím + nhãn trong không gian thấp của cover screen xoay ngang.
            keypadDp=Math.max(138,Math.min(165,(int)(shDp*0.43f)));
        }else if(compact){
            keypadDp=Math.max(285,Math.min(330,(int)(shDp*0.32f)));
        }else{
            keypadDp=310;
        }
        root.addView(keypadHost,new LinearLayout.LayoutParams(-1,dp(keypadDp)));
        setContentView(root);

        addCalc.setOnClickListener(v->{haptic(v);addCalcTable(true);});
        addCancel.setOnClickListener(v->{haptic(v);addCancelTable(true);}); del.setOnClickListener(v->{haptic(v);showMultiDeleteDialog();}); undoBtn.setOnClickListener(v->{haptic(v);undoDelete();}); share.setOnClickListener(v->{haptic(v);shareCurrent();}); quick1000.setOnClickListener(v->{haptic(v);cycleNumberFormat();}); tableBtn.setOnClickListener(v->{haptic(v);showTableManagerSheet();});
        renderAll();
    }

    void renderAll(){
        if(selected()==null&&!tables.isEmpty())selectedId=tables.get(0).id;
        tableBtn.setText("Bảng ("+tables.size()+")");
        quick1000.setText(formatSample());
        undoBtn.setEnabled(undoSnapshot!=null || lastDeleted!=null);
        updateCompactCurrentHeader();
        renderSidebar();renderGrid();renderKeypads();
    }


    void updateCompactCurrentHeader(){
        if(compactTableTitle==null)return;
        TableModel t=selected();
        if(t==null){
            compactTableTitle.setText("Chưa có bảng");
            compactGroupTitle.setText("");
            return;
        }
        compactTableTitle.setText(t.title);
        String groupName=groupNameFor(t.groupId);
        compactGroupTitle.setText(groupName.isEmpty()?"Chưa nhóm":groupName);
    }

    String groupNameFor(String gid){
        if(gid==null||gid.isEmpty())return "";
        for(GroupModel g:groups)if(gid.equals(g.id))return g.name;
        return "";
    }

    void renderSidebar(){
        if(sidebar==null)return;
        sidebar.removeAllViews();
        addSidebarSection(UNGROUPED,null);
        for(GroupModel g:groups)addSidebarSection(g.id,g);
        sidebar.setOnDragListener((v,e)->true);
    }

    void addSidebarSection(String gid,GroupModel group){
        if(group!=null){
            LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);gh.setPadding(dp(9),dp(8),dp(7),dp(7));gh.setBackgroundColor(groupBg);
            TextView n=text((collapsedGroups.contains(gid)?"▸ ":"▾ ")+group.name,compact?12:14,true);TextView sum=text(fmt(groupTotal(gid)),compact?11:13,true);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);sum.setTextColor(accent);gh.addView(n,w(0,dp(40),1));gh.addView(sum,w(0,dp(40),0.9f));gh.setTag("GROUP:"+gid);
            gh.setOnDragListener((v,e)->{if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED){v.setAlpha(.65f);return true;}if(e.getAction()==DragEvent.ACTION_DRAG_EXITED){v.setAlpha(1f);return true;}if(e.getAction()==DragEvent.ACTION_DROP){v.setAlpha(1f);String id=(String)e.getLocalState();TableModel t=findTable(id);if(t!=null){tables.remove(t);t.groupId=gid;tables.add(t);save();renderAll();}return true;}if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)v.setAlpha(1f);return true;});
            gh.setOnClickListener(v->{haptic(v);showGroupMenu(v,group);});
            gh.setOnLongClickListener(v->{haptic(v);showGroupMenu(v,group);return true;});
            installGroupQuickSwipe(gh,group);
            sidebar.addView(gh);
        }
        if(group!=null && collapsedGroups.contains(gid))return;
        ArrayList<TableModel> list=inGroup(gid); for(TableModel t:list)sidebar.addView(sidebarItem(t,gid));
    }

    View sidebarItem(TableModel t,String gid){
        LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.HORIZONTAL);item.setTag(t.id);item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8),dp(5),dp(6),dp(5));GradientDrawable itemBg=new GradientDrawable();
        itemBg.setColor(t.id.equals(selectedId)?selectedBg:Color.TRANSPARENT);
        itemBg.setCornerRadius(dp(12));
        item.setBackground(itemBg);

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(4),0,dp(2),0);
        TextView title=text(t.title,compact?14:16,true);
        title.setTextColor(t.id.equals(selectedId)?accent:ink);
        TextView meta=text(timeText(t.updated)+" · "+t.dataRowCount()+" dòng",compact?10:12,false);
        meta.setTextColor(muted);
        info.addView(title);info.addView(meta);

        TextView drag=text("≡",22,true);drag.setGravity(Gravity.CENTER);
        drag.setTextColor(t.id.equals(selectedId)?accent:muted);
        item.addView(info,new LinearLayout.LayoutParams(0,dp(62),1));
        item.addView(drag,new LinearLayout.LayoutParams(dp(42),dp(62)));

        View.OnClickListener select=v->{selectedId=t.id;activeRow=0;activeField="cancel".equals(t.type)?"qty":"price";explicitCellSelection=false;renderAll();};
        info.setOnClickListener(select);
        title.setOnClickListener(select);

        // Nhấn giữ bảng ở màn trong = vào chế độ chọn với bảng này được chọn sẵn.
        info.setOnLongClickListener(v->{haptic(v);showTableManagerSheet(t.id);return true;});

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
        gridHost.removeAllViews();gridScroll=null;gridRecycler=null;
        TableModel t=selected();if(t==null)return;
        if("cancel".equals(t.type))renderCancelGrid(t);else renderCalcGrid(t);
        pageIndicator.setText((tables.indexOf(t)+1)+"/"+tables.size());
        grandTotal.setText(fmt(t.total()));
        if(clearQtyBtn!=null)clearQtyBtn.setVisibility("cancel".equals(t.type)?View.VISIBLE:View.GONE);
        scrollActiveRowIntoView();
    }

    void renderCalcGrid(TableModel t){
        LinearLayout head=gridRow();
        head.addView(cell("STT",14,false,Gravity.CENTER),w(0,dp(compact?52:58),0.45f));
        head.addView(cell("Đơn giá",14,false,Gravity.CENTER),w(0,dp(compact?52:58),2));
        head.addView(cell("SL",14,false,Gravity.CENTER),w(0,dp(compact?52:58),1));
        head.addView(cell("Thành tiền",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,dp(compact?52:58),2));
        gridHost.addView(head);
        ensureBlankCalc(t);
        gridRecycler=new RecyclerView(this);
        gridRecycler.setLayoutManager(new LinearLayoutManager(this));
        gridRecycler.setItemAnimator(null);
        gridRecycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        gridRecycler.setAdapter(new CalcAdapter(t));
        gridHost.addView(gridRecycler,new LinearLayout.LayoutParams(-1,0,1));
    }

    void renderCancelGrid(TableModel t){
        LinearLayout head=gridRow();
        head.addView(cell("STT",14,false,Gravity.CENTER),w(0,dp(compact?52:58),0.45f));
        head.addView(cell("Tên đại lý",14,false,Gravity.START|Gravity.CENTER_VERTICAL),w(0,dp(58),3));
        head.addView(cell("Số lượng",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,dp(compact?52:58),2));
        gridHost.addView(head);
        ensureBlankCancel(t);
        gridRecycler=new RecyclerView(this);
        gridRecycler.setLayoutManager(new LinearLayoutManager(this));
        gridRecycler.setItemAnimator(null);
        gridRecycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        gridRecycler.setAdapter(new CancelAdapter(t));
        gridHost.addView(gridRecycler,new LinearLayout.LayoutParams(-1,0,1));
    }

    void resetCell(TextView v){
        v.clearAnimation();
        GradientDrawable d=new GradientDrawable();
        d.setColor(Color.WHITE);
        d.setStroke(dp(1),rule);
        v.setBackground(d);
        v.setTextColor(ink);
    }

    class CalcAdapter extends RecyclerView.Adapter<CalcAdapter.H>{
        final TableModel t;
        CalcAdapter(TableModel x){t=x;}
        class H extends RecyclerView.ViewHolder{
            LinearLayout row;TextView st,p,q,total;
            H(LinearLayout r,TextView a,TextView b,TextView c,TextView d){super(r);row=r;st=a;p=b;q=c;total=d;}
        }
        @Override public H onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout rr=gridRow();
            TextView st=cell("",14,false,Gravity.CENTER);
            TextView p=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            TextView q=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            TextView total=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            rr.addView(st,w(0,dp(compact?50:56),0.45f));
            rr.addView(p,w(0,dp(compact?50:56),2));
            rr.addView(q,w(0,dp(compact?50:56),1));
            rr.addView(total,w(0,dp(compact?50:56),2));
            return new H(rr,st,p,q,total);
        }
        @Override public void onBindViewHolder(H h,int row){
            CalcRow r=row<t.calcRows.size()?t.calcRows.get(row):null;
            h.st.setText(String.valueOf(row+1));
            h.p.setText(r==null||r.price==0?"":fmt(r.price));
            h.q.setText(r==null||r.qty==0?"":fmt(r.qty));
            h.total.setText(r==null||r.price==0||r.qty==0?"":fmt(r.price*r.qty));
            resetCell(h.st);resetCell(h.p);resetCell(h.q);resetCell(h.total);
            if(row==activeRow&&"price".equals(activeField))markActive(h.p);
            if(row==activeRow&&"qty".equals(activeField))markActive(h.q);
            h.st.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;explicitCellSelection=false;saveUiState();notifyDataSetChanged();renderKeypads();});
            h.p.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;activeField="price";explicitCellSelection=true;ensureRow(t,row);saveUiState();notifyDataSetChanged();renderKeypads();});
            h.q.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;activeField="qty";explicitCellSelection=true;ensureRow(t,row);saveUiState();notifyDataSetChanged();renderKeypads();});
            h.row.setOnLongClickListener(v->{haptic(v);showRowDeleteDialog(t,row);return true;});
        }
        @Override public int getItemCount(){return Math.max(8,t.calcRows.size());}
    }

    class CancelAdapter extends RecyclerView.Adapter<CancelAdapter.H>{
        final TableModel t;
        CancelAdapter(TableModel x){t=x;}
        class H extends RecyclerView.ViewHolder{
            LinearLayout row;TextView st,a,q;
            H(LinearLayout r,TextView x,TextView y,TextView z){super(r);row=r;st=x;a=y;q=z;}
        }
        @Override public H onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout rr=gridRow();
            TextView st=cell("",14,false,Gravity.CENTER);
            TextView a=cell("",16,false,Gravity.START|Gravity.CENTER_VERTICAL);
            TextView q=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            rr.addView(st,w(0,dp(compact?50:56),0.45f));
            rr.addView(a,w(0,dp(56),3));
            rr.addView(q,w(0,dp(compact?50:56),2));
            return new H(rr,st,a,q);
        }
        @Override public void onBindViewHolder(H h,int row){
            CancelRow r=row<t.cancelRows.size()?t.cancelRows.get(row):null;
            h.st.setText(String.valueOf(row+1));
            h.a.setText(r==null?"":r.agent);
            h.q.setText(r==null||r.qty==0?"":fmt(r.qty));
            resetCell(h.st);resetCell(h.a);resetCell(h.q);
            if(row==activeRow)markActive(h.q);
            h.a.setOnClickListener(v->{ensureCancelRow(t,row);editAgent(t,row);});
            h.q.setOnClickListener(v->{activeRow=row;pendingScrollRow=row;activeField="qty";explicitCellSelection=true;ensureCancelRow(t,row);saveUiState();notifyDataSetChanged();renderKeypads();});
            h.row.setOnLongClickListener(v->{haptic(v);showRowDeleteDialog(t,row);return true;});
        }
        @Override public int getItemCount(){return Math.max(8,t.cancelRows.size());}
    }

    void renderKeypads(){keypadHost.removeAllViews();TableModel t=selected();if(t==null)return;if("cancel".equals(t.type)){LinearLayout filler=new LinearLayout(this);keypadHost.addView(filler,w(0,-1,1));keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));}else{keypadHost.addView(buildPad("Đơn giá","price"),w(0,-1,1));keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));}}

    LinearLayout buildPad(String label,String field){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(4),dp(4),dp(4),dp(2));TextView lab=text(label,compact?14:15,field.equals(activeField));lab.setTextColor(field.equals(activeField)?accent:muted);lab.setGravity(Gravity.CENTER);wrap.addView(lab,new LinearLayout.LayoutParams(-1,dp(compactLandscape?20:28)));String[][] keys={{"7","8","9"},{"4","5","6"},{"1","2","3"},{"⌫","0","C"}};for(String[] row:keys){LinearLayout rr=new LinearLayout(this);rr.setPadding(0,0,dp(3),dp(3));for(String k:row){Button b=keyButton(k);b.setOnClickListener(v->{haptic(v);handleKey(field,k);});rr.addView(b,w(0,-1,1));}wrap.addView(rr,new LinearLayout.LayoutParams(-1,0,1));}return wrap;
    }

    void handleKey(String field,String key){
        TableModel t=selected();if(t==null)return;if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}

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
        if(fastInputMode==1 && "qty".equals(field) && !"C".equals(key) && !"⌫".equals(key) && !"cancel".equals(t.type)){
            // Vẫn cho nhập nhiều chữ số; Enter/Tab mới xác nhận chuyển dòng.
        }
        save();saveUiState();renderGrid();renderSidebar();renderKeypads();
    }




    String editDigits(String s,String key){
        if(s==null)s="";
        if("C".equals(key))return "";
        if("⌫".equals(key))return s.length()>0?s.substring(0,s.length()-1):"";
        return s+key;
    }


    String stateJson(){
        try{
            JSONObject root=new JSONObject();
            JSONArray ga=new JSONArray();for(GroupModel g:groups)ga.put(g.json());
            JSONArray ta=new JSONArray();for(TableModel t:tables)ta.put(t.json());
            root.put("groups",ga).put("tables",ta);
            root.put("selectedId",selectedId==null?"":selectedId);
            root.put("activeRow",activeRow).put("activeField",activeField);
            root.put("numberFormatMode",numberFormatMode).put("fastInputMode",fastInputMode);
            root.put("shareHideBlank",shareHideBlank);
            JSONArray ca=new JSONArray();for(String s:collapsedGroups)ca.put(s);root.put("collapsed",ca);
            return root.toString();
        }catch(Exception e){return "{}";}
    }

    void pushUndo(String label){
        undoSnapshot=stateJson();
        undoLabel=label==null?"Thao tác":label;
        lastDeleted=null;lastDeletedIndex=-1;
        if(undoBtn!=null)undoBtn.setEnabled(true);
    }

    void restoreStateJson(String json){
        try{
            JSONObject o=new JSONObject(json);
            groups.clear();tables.clear();collapsedGroups.clear();
            JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));
            JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++)tables.add(TableModel.from(ta.getJSONObject(i)));
            selectedId=o.optString("selectedId","");
            if(findTable(selectedId)==null)selectedId=tables.isEmpty()?null:tables.get(0).id;
            activeRow=Math.max(0,o.optInt("activeRow",0));
            activeField=o.optString("activeField","price");
            numberFormatMode=o.optInt("numberFormatMode",numberFormatMode);
            fastInputMode=o.optInt("fastInputMode",fastInputMode);
            shareHideBlank=o.optBoolean("shareHideBlank",shareHideBlank);
            JSONArray ca=o.optJSONArray("collapsed");if(ca!=null)for(int i=0;i<ca.length();i++)collapsedGroups.add(ca.optString(i));
            save();renderAll();
        }catch(Exception e){Toast.makeText(this,"Không thể khôi phục dữ liệu",Toast.LENGTH_LONG).show();}
    }

    void saveUiState(){
        StringBuilder c=new StringBuilder();
        for(String s:collapsedGroups){if(c.length()>0)c.append("|");c.append(s);}
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
            .putString(SELECTED_KEY,selectedId)
            .putInt(ACTIVE_ROW_KEY,activeRow)
            .putString(ACTIVE_FIELD_KEY,activeField)
            .putString(COLLAPSED_KEY,c.toString())
            .putInt(FAST_INPUT_KEY,fastInputMode)
            .putBoolean(SHARE_BLANK_KEY,shareHideBlank)
            .apply();
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
        if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this)
            .setTitle("Dòng "+(row+1))
            .setItems(new String[]{"Xóa dòng"},(d,i)->{
                pushUndo("Xóa dòng");
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
                        pushUndo("Xóa nhiều bảng");
                        TableModel undo=null;int undoIndex=-1;
                        for(int i=tables.size()-1;i>=0;i--){
                            if(i<checked.length && checked[i] && !tables.get(i).locked){
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
                        if(dx<0)animateTablePageChange(true); else animateTablePageChange(false);
                        return true;
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
        explicitCellSelection=false;saveUiState();haptic(gridHost);renderAll();
    }

    void selectPreviousTable(){
        if(tables.size()<2)return;
        TableModel cur=selected();int idx=cur==null?0:tables.indexOf(cur);
        idx=(idx-1+tables.size())%tables.size();
        selectedId=tables.get(idx).id;activeRow=0;pendingScrollRow=0;
        activeField="cancel".equals(tables.get(idx).type)?"qty":"price";
        explicitCellSelection=false;saveUiState();haptic(gridHost);renderAll();
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

    void showTableManagerSheet(){showTableManagerSheet(null);}

    void showTableManagerSheet(String preselectId){
        final Dialog dlg=new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        topInsetSpacer=new View(this);
        topInsetSpacer.setBackgroundColor(Color.WHITE);
        root.addView(topInsetSpacer,new LinearLayout.LayoutParams(-1,0));
        applyTopSystemInset();
        root.setPadding(dp(10),dp(8),dp(10),dp(10));
        root.setBackgroundColor(Color.rgb(248,250,252));

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Quản lý bảng & nhóm",20,true);
        TextView hint=text("Vuốt trái: thao tác • Vuốt phải: chọn • Giữ ≡: kéo",11,false);
        hint.setTextColor(muted);
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(title);titleBox.addView(hint);
        Button close=smallActionButton("Đóng");
        header.addView(titleBox,new LinearLayout.LayoutParams(0,dp(60),1));
        header.addView(close,new LinearLayout.LayoutParams(dp(76),dp(48)));
        root.addView(header);

        final String[] searchQuery={""};
        EditText search=new EditText(this);
        search.setHint("Tìm bảng, nhóm hoặc tên đại lý…");
        search.setSingleLine();
        search.setTextSize(14);
        root.addView(search,new LinearLayout.LayoutParams(-1,dp(46)));

        LinearLayout tools=new LinearLayout(this);tools.setPadding(0,dp(4),0,dp(4));
        Button backup=smallActionButton("Sao lưu");
        Button restore=smallActionButton("Khôi phục");
        Button sort=smallActionButton("Sắp xếp");
        Button settings=smallActionButton("Cài đặt");
        tools.addView(backup,new LinearLayout.LayoutParams(0,dp(44),1));
        tools.addView(restore,new LinearLayout.LayoutParams(0,dp(44),1));
        tools.addView(sort,new LinearLayout.LayoutParams(0,dp(44),1));
        tools.addView(settings,new LinearLayout.LayoutParams(0,dp(44),1));
        root.addView(tools);

        final HashSet<String> selectedIds=new HashSet<>();
        if(preselectId!=null&&!preselectId.isEmpty())selectedIds.add(preselectId);
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
            for(TableModel t:managerTablesForGroup(UNGROUPED,searchQuery[0])){
                list.addView(managerTableRow(t,UNGROUPED,selectedIds,dlg,rebuild[0]));
            }

            for(GroupModel g:new ArrayList<>(groups)){
                if(!managerGroupVisible(g,searchQuery[0]))continue;
                LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);
                gh.setPadding(dp(8),dp(5),dp(4),dp(5));gh.setBackgroundColor(groupBg);
                TextView gname=text((collapsedGroups.contains(g.id)?"▸ ":"▾ ")+g.name,16,true);
                TextView sum=text(inGroup(g.id).size()+" bảng • "+fmt(groupTotal(g.id)),12,true);sum.setTextColor(accent);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);sum.setTextColor(accent);
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
                    for(TableModel t:managerTablesForGroup(g.id,searchQuery[0])){
                        list.addView(managerTableRow(t,g.id,selectedIds,dlg,rebuild[0]));
                    }
                }
            }
        };
        rebuild[0].run();

        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){searchQuery[0]=s.toString().trim();rebuild[0].run();}
            public void afterTextChanged(Editable e){}
        });
        backup.setOnClickListener(v->{haptic(v);startBackup();});
        restore.setOnClickListener(v->{haptic(v);startRestore();});
        sort.setOnClickListener(v->{haptic(v);showSortDialog(()->rebuild[0].run());});
        settings.setOnClickListener(v->{haptic(v);showSettingsDialog();});

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


    boolean tableMatches(TableModel t,String q){
        if(q==null||q.trim().isEmpty())return true;
        q=q.toLowerCase(Locale.getDefault());
        if(t.title!=null&&t.title.toLowerCase(Locale.getDefault()).contains(q))return true;
        if("cancel".equals(t.type))for(CancelRow r:t.cancelRows)if(r.agent!=null&&r.agent.toLowerCase(Locale.getDefault()).contains(q))return true;
        return false;
    }

    boolean managerGroupVisible(GroupModel g,String q){
        if(q==null||q.trim().isEmpty())return true;
        String z=q.toLowerCase(Locale.getDefault());
        if(g.name!=null&&g.name.toLowerCase(Locale.getDefault()).contains(z))return true;
        for(TableModel t:inGroup(g.id))if(tableMatches(t,q))return true;
        return false;
    }

    ArrayList<TableModel> managerTablesForGroup(String gid,String q){
        ArrayList<TableModel> a=new ArrayList<>();
        for(TableModel t:inGroup(gid))if(tableMatches(t,q))a.add(t);
        Comparator<TableModel> c=null;
        if(managerSortMode==1)c=(x,y)->x.title.compareToIgnoreCase(y.title);
        else if(managerSortMode==2)c=(x,y)->Long.compare(y.updated,x.updated);
        else if(managerSortMode==3)c=(x,y)->Long.compare(x.updated,y.updated);
        if(c!=null)Collections.sort(a,c);
        return a;
    }

    void showSortDialog(Runnable after){
        String[] opts={"Thứ tự kéo tay","Tên A → Z","Mới cập nhật","Cũ nhất"};
        new AlertDialog.Builder(this).setTitle("Sắp xếp bảng").setSingleChoiceItems(opts,managerSortMode,(d,i)->{
            managerSortMode=i;d.dismiss();if(after!=null)after.run();
        }).show();
    }

    View managerTableRow(TableModel t,String gid,HashSet<String> selectedIds,Dialog dlg,Runnable rebuild){
        LinearLayout row=new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8),dp(5),dp(4),dp(5));
        row.setBackgroundColor(t.id.equals(selectedId)?selectedBg:Color.WHITE);

        CheckBox cb=new CheckBox(this);
        cb.setChecked(selectedIds.contains(t.id));
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(navy));

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        TextView name=text(t.title,16,true);
        TextView meta=text((t.locked?"🔒 • ":"")+t.dataRowCount()+" dòng • "+fmt(t.total()),12,false);meta.setTextColor(muted);
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
        h.setTextColor(accent);
        h.setBackgroundColor(groupBg);
        return h;
    }

    boolean managerGroupDrop(DragEvent e,String gid,Dialog dlg,Runnable rebuild){
        if(e.getAction()==DragEvent.ACTION_DROP){
            Object st=e.getLocalState();if(!(st instanceof String))return true;
            TableModel t=findTable((String)st);
            if(t!=null){pushUndo("Kéo bảng sang nhóm");tables.remove(t);t.groupId=gid;tables.add(t);save();rebuild.run();renderAll();}
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
        String[] actions={"Mở bảng","Copy bảng",t.locked?"Mở khóa bảng":"Khóa bảng","Đổi tên","Chuyển nhóm","Xóa bảng"};
        new AlertDialog.Builder(this).setTitle(t.title).setItems(actions,(d,i)->{
            if(i==0){selectedId=t.id;manager.dismiss();renderAll();}
            else if(i==1){showCopyOptions(t);rebuild.run();}
            else if(i==2){toggleLock(t);rebuild.run();}
            else if(i==3){selectedId=t.id;manager.dismiss();renameCurrent();}
            else if(i==4)showMoveOneDialog(t,manager,rebuild);
            else if(i==5){if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Xóa "+t.title+"?")
                    .setPositiveButton("Xóa",(x,w)->{pushUndo("Xóa bảng");tables.remove(t);if(t.id.equals(selectedId))selectedId=tables.isEmpty()?null:tables.get(0).id;save();rebuild.run();renderAll();})
                    .setNegativeButton("Hủy",null).show();}
        }).show();
    }

    void showManagerGroupActions(GroupModel g,Dialog manager,Runnable rebuild){
        String[] actions={"Thu gọn / Mở rộng","Đổi tên nhóm","Copy cả nhóm","Xóa toàn bộ số lượng trong nhóm","Xóa nhóm (giữ bảng)"};
        new AlertDialog.Builder(this).setTitle(g.name).setItems(actions,(d,i)->{
            if(i==0){if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);saveUiState();rebuild.run();}
            else if(i==1){manager.dismiss();renameGroup(g);}
            else if(i==2){copyGroup(g);rebuild.run();}
            else if(i==3)confirmClearGroupQuantities(g,()->{rebuild.run();renderAll();});
            else{
                pushUndo("Xóa nhóm");
                for(TableModel t:tables)if(g.id.equals(t.groupId))t.groupId=UNGROUPED;
                groups.remove(g);save();rebuild.run();renderAll();
            }
        }).show();
    }

    void showMoveOneDialog(TableModel t,Dialog manager,Runnable rebuild){
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);
        new AlertDialog.Builder(this).setTitle("Chuyển "+t.title).setItems(names.toArray(new String[0]),(d,i)->{
            pushUndo("Chuyển nhóm");
            t.groupId=i==0?UNGROUPED:groups.get(i-1).id;
            tables.remove(t);tables.add(t);save();rebuild.run();renderAll();
        }).show();
    }

    void showMoveSelectedDialog(HashSet<String> selectedIds,Dialog manager,Runnable rebuild){
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);
        new AlertDialog.Builder(this).setTitle("Chuyển "+selectedIds.size()+" bảng").setItems(names.toArray(new String[0]),(d,i)->{
            String gid=i==0?UNGROUPED:groups.get(i-1).id;
            pushUndo("Chuyển nhiều bảng");
            ArrayList<TableModel> moving=new ArrayList<>();
            for(TableModel t:new ArrayList<>(tables))if(selectedIds.contains(t.id)){tables.remove(t);t.groupId=gid;moving.add(t);}
            tables.addAll(moving);save();rebuild.run();renderAll();
        }).show();
    }

    void confirmDeleteSelected(HashSet<String> selectedIds,Dialog manager){
        int n=selectedIds.size();
        new AlertDialog.Builder(this).setTitle("Xóa "+n+" bảng?")
            .setMessage("Bảng đang khóa sẽ được giữ lại.")
            .setPositiveButton("Xóa",(d,w)->{
                pushUndo("Xóa nhiều bảng");
                TableModel undo=null;int undoIndex=-1;
                for(int i=tables.size()-1;i>=0;i--)if(selectedIds.contains(tables.get(i).id)&&!tables.get(i).locked){undo=tables.get(i);undoIndex=i;tables.remove(i);}
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
        String[] a={"Copy bảng",t.locked?"Mở khóa":"Khóa bảng","Đổi tên","Chuyển nhóm","Xóa"};
        new AlertDialog.Builder(this).setTitle(t.title).setItems(a,(d,i)->{
            selectedId=t.id;
            if(i==0)showCopyOptions(t);
            else if(i==1)toggleLock(t);
            else if(i==2)renameCurrent();
            else if(i==3)moveCurrentGroup();
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
    void showGroupMenu(View anchor,GroupModel g){
        PopupMenu p=new PopupMenu(this,anchor);
        p.getMenu().add(collapsedGroups.contains(g.id)?"Mở rộng nhóm":"Thu gọn nhóm");
        p.getMenu().add("Đổi tên nhóm");
        p.getMenu().add("Copy cả nhóm");
        p.getMenu().add("Xóa toàn bộ số lượng trong nhóm");
        p.getMenu().add("Xóa nhóm (giữ bảng)");
        p.setOnMenuItemClickListener(i->{
            String s=i.getTitle().toString();
            if(s.startsWith("Mở")||s.startsWith("Thu")){
                if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);
                renderSidebar();
            }else if(s.startsWith("Đổi")){
                renameGroup(g);
            }else if(s.startsWith("Copy")){
                copyGroup(g);
            }else if(s.startsWith("Xóa toàn")){
                confirmClearGroupQuantities(g,()->renderAll());
            }else{
                deleteGroup(g);
            }
            return true;
        });
        p.show();
    }


    void showCopyOptions(TableModel src){
        if(src==null)return;
        String[] opts="cancel".equals(src.type)
            ?new String[]{"Copy toàn bộ","Copy tên đại lý, xóa số lượng","Copy bảng trống"}
            :new String[]{"Copy toàn bộ","Copy đơn giá, xóa số lượng","Copy bảng trống"};
        new AlertDialog.Builder(this).setTitle("Copy "+src.title).setItems(opts,(d,i)->copyTableMode(src,i)).show();
    }

    void copyTable(TableModel src){copyTableMode(src,0);}

    void copyTableMode(TableModel src,int mode){
        if(src==null)return;
        TableModel t=new TableModel();
        t.id=id();t.type=src.type;t.title=src.title+" - Bản sao";t.groupId=src.groupId;t.updated=System.currentTimeMillis();
        if("cancel".equals(src.type)){
            if(mode==2)t.cancelRows.add(new CancelRow("",0));
            else{
                for(CancelRow r:src.cancelRows){
                    if(r.blank())continue;
                    t.cancelRows.add(new CancelRow(r.agent,mode==1?0:r.qty));
                }
                ensureBlankCancel(t);
            }
        }else{
            if(mode==2)t.calcRows.add(new CalcRow());
            else{
                for(CalcRow r:src.calcRows){
                    if(r.blank())continue;
                    CalcRow c=new CalcRow();c.price=r.price;c.qty=mode==1?0:r.qty;t.calcRows.add(c);
                }
                ensureBlankCalc(t);
            }
        }
        int idx=tables.indexOf(src);tables.add(Math.min(tables.size(),idx+1),t);
        selectedId=t.id;activeRow=0;pendingScrollRow=0;activeField="cancel".equals(t.type)?"qty":"price";
        save();renderAll();Toast.makeText(this,"Đã copy bảng",Toast.LENGTH_SHORT).show();
    }

    void toggleLock(TableModel t){
        if(t==null)return;t.locked=!t.locked;t.updated=System.currentTimeMillis();save();renderAll();
        Toast.makeText(this,t.locked?"Đã khóa bảng":"Đã mở khóa bảng",Toast.LENGTH_SHORT).show();
    }

    void copyGroup(GroupModel g){
        if(g==null)return;
        GroupModel ng=new GroupModel();ng.id=id();ng.name=g.name+" - Bản sao";groups.add(ng);
        ArrayList<TableModel> srcs=new ArrayList<>(inGroup(g.id));
        for(TableModel s:srcs){
            TableModel t=new TableModel();t.id=id();t.type=s.type;t.title=s.title+" - Bản sao";t.groupId=ng.id;t.updated=System.currentTimeMillis();
            if("cancel".equals(s.type)){for(CancelRow r:s.cancelRows)if(!r.blank())t.cancelRows.add(new CancelRow(r.agent,r.qty));ensureBlankCancel(t);}
            else{for(CalcRow r:s.calcRows)if(!r.blank()){CalcRow c=new CalcRow();c.price=r.price;c.qty=r.qty;t.calcRows.add(c);}ensureBlankCalc(t);}
            tables.add(t);
        }
        save();renderAll();Toast.makeText(this,"Đã copy nhóm",Toast.LENGTH_SHORT).show();
    }

    void confirmClearCurrentQuantities(){
        TableModel t=selected();
        if(t==null||!"cancel".equals(t.type))return;if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this)
            .setTitle("Xóa toàn bộ số lượng?")
            .setMessage("Tên đại lý sẽ được giữ nguyên.")
            .setPositiveButton("Xóa SL",(d,w)->{
                pushUndo("Xóa số lượng");
                for(CancelRow r:t.cancelRows)r.qty=0;
                t.updated=System.currentTimeMillis();
                ensureBlankCancel(t);
                save();renderAll();
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

    void clearQuantitiesInGroup(GroupModel g){
        if(g==null)return;
        for(TableModel t:tables){
            if(!g.id.equals(t.groupId)||t.locked)continue;
            if("cancel".equals(t.type)){
                for(CancelRow r:t.cancelRows)r.qty=0;
                ensureBlankCancel(t);
            }else{
                for(CalcRow r:t.calcRows)r.qty=0;
                ensureBlankCalc(t);
            }
            t.updated=System.currentTimeMillis();
        }
        save();
    }

    void confirmClearGroupQuantities(GroupModel g,Runnable after){
        if(g==null)return;
        new AlertDialog.Builder(this)
            .setTitle("Xóa số lượng trong "+g.name+"?")
            .setMessage("Đơn giá và tên đại lý được giữ nguyên; chỉ cột số lượng của tất cả bảng trong nhóm bị xóa.")
            .setPositiveButton("Xóa số lượng",(d,w)->{
                pushUndo("Xóa số lượng nhóm");
                clearQuantitiesInGroup(g);
                if(after!=null)after.run();
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

    void addCalcTable(boolean select){TableModel t=new TableModel();t.id=id();t.type="calc";t.title="Bảng "+(tables.size()+1);t.updated=System.currentTimeMillis();t.calcRows.add(new CalcRow());tables.add(t);if(select)selectedId=t.id;save();if(select)renderAll();}
    void addCancelTable(boolean select){TableModel t=new TableModel();t.id=id();t.type="cancel";t.title="Hủy vé";t.updated=System.currentTimeMillis();t.cancelRows.add(new CancelRow("",0));tables.add(t);if(select)selectedId=t.id;save();if(select)renderAll();}
    void deleteCurrent(){TableModel t=selected();if(t==null)return;if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Xóa bảng?").setMessage(t.title).setPositiveButton("Xóa",(d,w)->{pushUndo("Xóa bảng");lastDeleted=t;lastDeletedIndex=tables.indexOf(t);tables.remove(t);selectedId=tables.isEmpty()?null:tables.get(Math.max(0,Math.min(lastDeletedIndex,tables.size()-1))).id;save();renderAll();}).setNegativeButton("Hủy",null).show();}
    void undoDelete(){if(undoSnapshot!=null){String s=undoSnapshot;String label=undoLabel;undoSnapshot=null;undoLabel="";restoreStateJson(s);Toast.makeText(this,"Đã hoàn tác: "+label,Toast.LENGTH_SHORT).show();return;}if(lastDeleted==null)return;tables.add(Math.max(0,Math.min(lastDeletedIndex,tables.size())),lastDeleted);selectedId=lastDeleted.id;lastDeleted=null;lastDeletedIndex=-1;save();renderAll();}
    void renameCurrent(){TableModel t=selected();if(t==null)return;EditText e=new EditText(this);e.setText(t.title);e.setSelectAllOnFocus(true);e.setSingleLine();AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Đổi tên bảng").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){t.title=s;t.updated=System.currentTimeMillis();save();renderAll();}}).setNegativeButton("Hủy",null).create();dlg.setOnShowListener(x->{e.requestFocus();e.selectAll();dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dlg.show();}
    void createGroupDialog(){EditText e=new EditText(this);e.setHint("Tên nhóm");new AlertDialog.Builder(this).setTitle("Tạo nhóm bảng").setView(padded(e)).setPositiveButton("Tạo",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){GroupModel g=new GroupModel();g.id=id();g.name=s;groups.add(g);save();renderAll();}}).setNegativeButton("Hủy",null).show();}
    void moveCurrentGroup(){TableModel t=selected();if(t==null)return;ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);new AlertDialog.Builder(this).setTitle("Chuyển vào nhóm").setItems(names.toArray(new String[0]),(d,i)->{pushUndo("Chuyển nhóm");t.groupId=i==0?UNGROUPED:groups.get(i-1).id;save();renderAll();}).show();}
    void renameGroup(GroupModel g){EditText e=new EditText(this);e.setText(g.name);new AlertDialog.Builder(this).setTitle("Đổi tên nhóm").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){g.name=s;save();renderAll();}}).setNegativeButton("Hủy",null).show();}
    void deleteGroup(GroupModel g){pushUndo("Xóa nhóm");for(TableModel t:tables)if(g.id.equals(t.groupId))t.groupId=UNGROUPED;groups.remove(g);save();renderAll();}

    void editAgent(TableModel t,int row){if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}activeRow=row;CancelRow r=t.cancelRows.get(row);EditText e=new EditText(this);e.setHint("Tên đại lý");e.setText(r.agent);e.setSingleLine();AlertDialog dlg=new AlertDialog.Builder(this).setTitle("Tên đại lý").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{r.agent=e.getText().toString().trim();t.updated=System.currentTimeMillis();ensureBlankCancel(t);save();renderAll();}).setNegativeButton("Hủy",null).create();dlg.setOnShowListener(x->{e.requestFocus();dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dlg.show();}

    void ensureRow(TableModel t,int row){while(t.calcRows.size()<=row)t.calcRows.add(new CalcRow());}
    void ensureCancelRow(TableModel t,int row){while(t.cancelRows.size()<=row)t.cancelRows.add(new CancelRow("",0));}
    void ensureBlankCalc(TableModel t){if(t.calcRows.isEmpty()||!t.calcRows.get(t.calcRows.size()-1).blank())t.calcRows.add(new CalcRow());}
    void ensureBlankCancel(TableModel t){if(t.cancelRows.isEmpty()||!t.cancelRows.get(t.cancelRows.size()-1).blank())t.cancelRows.add(new CancelRow("",0));}


    void startBackup(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE,"ListCalculatorFold2_Backup_"+new SimpleDateFormat("yyyyMMdd_HHmm",Locale.getDefault()).format(new Date())+".json");
        startActivityForResult(i,REQ_BACKUP);
    }

    void startRestore(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");
        startActivityForResult(i,REQ_RESTORE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if(requestCode==REQ_BACKUP){
                try(OutputStream os=getContentResolver().openOutputStream(uri)){
                    os.write(stateJson().getBytes("UTF-8"));
                }
                Toast.makeText(this,"Đã sao lưu dữ liệu",Toast.LENGTH_SHORT).show();
            }else if(requestCode==REQ_RESTORE){
                StringBuilder s=new StringBuilder();
                try(BufferedReader br=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),"UTF-8"))){
                    String line;while((line=br.readLine())!=null)s.append(line);
                }
                new AlertDialog.Builder(this).setTitle("Khôi phục dữ liệu?")
                    .setMessage("Dữ liệu hiện tại sẽ được thay bằng bản sao lưu.")
                    .setPositiveButton("Khôi phục",(d,w)->{pushUndo("Khôi phục backup");restoreStateJson(s.toString());})
                    .setNegativeButton("Hủy",null).show();
            }
        }catch(Exception e){Toast.makeText(this,"Không xử lý được file: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    void showSettingsDialog(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),0);
        TextView t=text("Chế độ nhập",14,true);box.addView(t);
        RadioGroup rg=new RadioGroup(this);
        String[] labels={"Kiểu List Calculator","Enter/Tab tự chuyển ô","Thủ công"};
        for(int i=0;i<labels.length;i++){RadioButton r=new RadioButton(this);r.setText(labels[i]);r.setId(100+i);rg.addView(r);}
        rg.check(100+fastInputMode);box.addView(rg);
        CheckBox cb=new CheckBox(this);cb.setText("Ẩn dòng trống khi chia sẻ ảnh");cb.setChecked(shareHideBlank);box.addView(cb);
        new AlertDialog.Builder(this).setTitle("Cài đặt").setView(box)
            .setPositiveButton("Lưu",(d,w)->{
                int id=rg.getCheckedRadioButtonId();fastInputMode=Math.max(0,id-100);
                shareHideBlank=cb.isChecked();
                saveUiState();Toast.makeText(this,"Đã lưu cài đặt",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Hủy",null).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e){
        if(e.getAction()!=KeyEvent.ACTION_DOWN)return super.dispatchKeyEvent(e);
        if(e.isCtrlPressed()&&e.getKeyCode()==KeyEvent.KEYCODE_Z){undoDelete();return true;}
        if(e.isCtrlPressed()&&e.getKeyCode()==KeyEvent.KEYCODE_C){showCopyOptions(selected());return true;}
        if(e.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT){selectPreviousTable();return true;}
        if(e.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT){selectNextTable();return true;}
        if(e.getKeyCode()==KeyEvent.KEYCODE_TAB||e.getKeyCode()==KeyEvent.KEYCODE_ENTER){
            TableModel t=selected();if(t==null)return true;
            if("cancel".equals(t.type)){activeRow++;ensureCancelRow(t,activeRow);activeField="qty";}
            else if("price".equals(activeField)){activeField="qty";}
            else{activeRow++;ensureRow(t,activeRow);activeField="price";}
            pendingScrollRow=activeRow;explicitCellSelection=false;saveUiState();renderGrid();renderKeypads();return true;
        }
        int uc=e.getUnicodeChar();
        if(uc>='0'&&uc<='9'){handleKey(activeField,String.valueOf((char)uc));return true;}
        if(e.getKeyCode()==KeyEvent.KEYCODE_DEL){handleKey(activeField,"⌫");return true;}
        return super.dispatchKeyEvent(e);
    }

    void shareCurrent(){TableModel t=selected();if(t==null)return;try{LinearLayout report=buildShareView(t);int width=Math.min(dp(900),Math.max(dp(560),getResources().getDisplayMetrics().widthPixels));report.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));report.layout(0,0,width,report.getMeasuredHeight());Bitmap bmp=Bitmap.createBitmap(width,Math.max(1,report.getMeasuredHeight()),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bmp);c.drawColor(Color.WHITE);report.draw(c);File dir=new File(getCacheDir(),"share");dir.mkdirs();File f=new File(dir,"bang-"+System.currentTimeMillis()+".png");try(FileOutputStream os=new FileOutputStream(f)){bmp.compress(Bitmap.CompressFormat.PNG,100,os);}bmp.recycle();Uri uri=Uri.parse("content://com.vinh.listcalculatorfold2.share/"+Uri.encode(f.getName()));Intent send=new Intent(Intent.ACTION_SEND);send.setType("image/png");send.putExtra(Intent.EXTRA_STREAM,uri);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,"Chia sẻ ảnh"));}catch(Exception e){Toast.makeText(this,"Không tạo được ảnh",Toast.LENGTH_LONG).show();}}
    LinearLayout buildShareView(TableModel t){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(26),dp(22),dp(26),dp(24));r.setBackgroundColor(Color.WHITE);
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        try{
            ImageView logo=new ImageView(this);logo.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
            titleRow.addView(logo,new LinearLayout.LayoutParams(dp(46),dp(46)));
        }catch(Exception ignored){}
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);titleBox.setPadding(dp(10),0,0,0);
        TextView h=text(t.title,23,true);h.setTextColor(Color.BLACK);titleBox.addView(h);
        String gn=groupNameFor(t.groupId);
        if(!gn.isEmpty()){TextView g=text(gn,12,false);g.setTextColor(muted);titleBox.addView(g);}
        titleRow.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));r.addView(titleRow);
        TextView dt=text(new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date()),12,false);dt.setTextColor(Color.GRAY);r.addView(dt);
        Space sp=new Space(this);r.addView(sp,new LinearLayout.LayoutParams(1,dp(12)));
        if("cancel".equals(t.type)){
            LinearLayout hh=shareRow();hh.addView(shareCell("Tên đại lý",true,Gravity.START),w(0,dp(42),3));hh.addView(shareCell("Số lượng",true,Gravity.END),w(0,dp(42),1));r.addView(hh);
            for(CancelRow x:t.cancelRows){if(shareHideBlank&&x.blank())continue;LinearLayout rr=shareRow();rr.addView(shareCell(x.agent,false,Gravity.START),w(0,dp(40),3));rr.addView(shareCell(fmt(x.qty),false,Gravity.END),w(0,dp(40),1));r.addView(rr);}
        }else{
            LinearLayout hh=shareRow();hh.addView(shareCell("Đơn giá",true,Gravity.END),w(0,dp(42),2));hh.addView(shareCell("SL",true,Gravity.END),w(0,dp(42),1));hh.addView(shareCell("Thành tiền",true,Gravity.END),w(0,dp(42),2));r.addView(hh);
            for(CalcRow x:t.calcRows){if(shareHideBlank&&x.blank())continue;LinearLayout rr=shareRow();rr.addView(shareCell(fmt(x.price),false,Gravity.END),w(0,dp(40),2));rr.addView(shareCell(fmt(x.qty),false,Gravity.END),w(0,dp(40),1));rr.addView(shareCell(fmt(x.price*x.qty),false,Gravity.END),w(0,dp(40),2));r.addView(rr);}
        }
        TextView sum=text("TỔNG: "+fmt(t.total()),22,true);sum.setTextColor(accent);sum.setGravity(Gravity.END);sum.setPadding(0,dp(14),0,0);r.addView(sum);return r;
    }

    void save(){try{JSONObject root=new JSONObject();JSONArray ga=new JSONArray();for(GroupModel g:groups)ga.put(g.json());JSONArray ta=new JSONArray();for(TableModel t:tables)ta.put(t.json());root.put("groups",ga).put("tables",ta);getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(DATA,root.toString()).apply();saveUiState();}catch(Exception ignored){}}
    void load(){String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(DATA,null);if(s!=null){try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++)tables.add(TableModel.from(ta.getJSONObject(i)));return;}catch(Exception ignored){}}migrateOld();}
    void migrateOld(){String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(OLD_DATA,null);if(s==null)return;try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++){JSONObject x=ta.getJSONObject(i);TableModel t=new TableModel();t.id=x.optString("id",id());t.type=x.optString("type","calc");t.title=x.optString("title","Bảng");t.groupId=x.optString("groupId","");t.updated=System.currentTimeMillis();if("cancel".equals(t.type)){JSONArray c=x.optJSONArray("cancelRows");if(c!=null)for(int j=0;j<c.length();j++){JSONObject z=c.optJSONObject(j);t.cancelRows.add(new CancelRow(z.optString("agent"),z.optLong("qty")));}}else{JSONArray v=x.optJSONArray("values");if(v!=null)for(int j=0;j<v.length();j++){CalcRow cr=new CalcRow();cr.price=v.optDouble(j);cr.qty=1;t.calcRows.add(cr);}}tables.add(t);}save();}catch(Exception ignored){}}

    TableModel selected(){return findTable(selectedId);}TableModel findTable(String id){if(id==null)return null;for(TableModel t:tables)if(id.equals(t.id))return t;return null;}ArrayList<TableModel> inGroup(String gid){ArrayList<TableModel> a=new ArrayList<>();for(TableModel t:tables)if(gid.equals(t.groupId))a.add(t);return a;}double groupTotal(String gid){double x=0;for(TableModel t:tables)if(gid.equals(t.groupId))x+=t.total();return x;}

    static class GroupModel{String id,name;JSONObject json()throws Exception{return new JSONObject().put("id",id).put("name",name);}static GroupModel from(JSONObject o){GroupModel g=new GroupModel();g.id=o.optString("id");g.name=o.optString("name","Nhóm");return g;}}
    static class CalcRow{double price,qty;boolean blank(){return price==0&&qty==0;}JSONObject json()throws Exception{return new JSONObject().put("price",price).put("qty",qty);}static CalcRow from(JSONObject o){CalcRow r=new CalcRow();r.price=o.optDouble("price");r.qty=o.optDouble("qty");return r;}}
    static class CancelRow{String agent;long qty;CancelRow(String a,long q){agent=a;qty=q;}boolean blank(){return (agent==null||agent.trim().isEmpty())&&qty==0;}JSONObject json()throws Exception{return new JSONObject().put("agent",agent).put("qty",qty);}static CancelRow from(JSONObject o){return new CancelRow(o.optString("agent"),o.optLong("qty"));}}
    static class TableModel{String id,type="calc",title="Bảng",groupId="";long updated;boolean locked=false;ArrayList<CalcRow> calcRows=new ArrayList<>();ArrayList<CancelRow> cancelRows=new ArrayList<>();double total(){double x=0;if("cancel".equals(type)){for(CancelRow r:cancelRows)x+=r.qty;}else for(CalcRow r:calcRows)x+=r.price*r.qty;return x;}int dataRowCount(){int n=0;if("cancel".equals(type)){for(CancelRow r:cancelRows)if(!r.blank())n++;}else for(CalcRow r:calcRows)if(!r.blank())n++;return n;}JSONObject json()throws Exception{JSONObject o=new JSONObject().put("id",id).put("type",type).put("title",title).put("groupId",groupId).put("updated",updated).put("locked",locked);JSONArray a=new JSONArray();for(CalcRow r:calcRows)a.put(r.json());o.put("calcRows",a);JSONArray c=new JSONArray();for(CancelRow r:cancelRows)c.put(r.json());o.put("cancelRows",c);return o;}static TableModel from(JSONObject o){TableModel t=new TableModel();t.id=o.optString("id");t.type=o.optString("type","calc");t.title=o.optString("title","Bảng");t.groupId=o.optString("groupId","");t.updated=o.optLong("updated",System.currentTimeMillis());t.locked=o.optBoolean("locked",false);JSONArray a=o.optJSONArray("calcRows");if(a!=null)for(int i=0;i<a.length();i++)t.calcRows.add(CalcRow.from(a.optJSONObject(i)));JSONArray c=o.optJSONArray("cancelRows");if(c!=null)for(int i=0;i<c.length();i++)t.cancelRows.add(CancelRow.from(c.optJSONObject(i)));return t;}}

    void markActive(TextView v){
        GradientDrawable d=new GradientDrawable();
        d.setColor(Color.rgb(254,242,242));
        d.setStroke(dp(2),red);
        v.setBackground(d);
        v.setTextColor(red);
        AlphaAnimation a=new AlphaAnimation(.72f,1f);
        a.setDuration(220);
        a.setRepeatMode(Animation.REVERSE);
        a.setRepeatCount(1);
        v.startAnimation(a);
    }


    void scrollActiveRowIntoView(){
        if(gridRecycler==null)return;
        final int row=pendingScrollRow>=0?pendingScrollRow:activeRow;
        gridRecycler.post(()->{
            RecyclerView.LayoutManager lm=gridRecycler.getLayoutManager();
            if(lm instanceof LinearLayoutManager){
                ((LinearLayoutManager)lm).scrollToPositionWithOffset(Math.max(0,row),dp(12));
            }else gridRecycler.scrollToPosition(Math.max(0,row));
            pendingScrollRow=-1;
        });
    }

    LinearLayout gridRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setBackgroundColor(paper);return r;}TextView cell(String s,int sp,boolean bold,int gravity){if(compact)sp=Math.max(11,sp-2);TextView v=text(s,sp,bold);v.setGravity(gravity);v.setPadding(dp(10),0,dp(10),0);GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setStroke(dp(1),rule);v.setBackground(d);return v;}LinearLayout shareRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}TextView shareCell(String s,boolean bold,int gravity){TextView v=text(s,14,bold);v.setGravity(gravity|Gravity.CENTER_VERTICAL);v.setPadding(dp(8),0,dp(8),0);GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setStroke(1,Color.LTGRAY);v.setBackground(d);return v;}
    Button topButton(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(compactLandscape?9.5f:(compact?11.0f:12.0f));
        b.setTextColor(ink);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(6),0,dp(6),0);

        GradientDrawable d=new GradientDrawable();
        d.setColor(Color.rgb(248,250,252));
        d.setStroke(dp(1),Color.rgb(226,232,240));
        d.setCornerRadius(dp(18));
        b.setBackground(d);
        b.setStateListAnimator(null);
        return b;
    }Button keyButton(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextSize(compactLandscape?18:(compact?23:25));
        b.setTypeface(android.graphics.Typeface.DEFAULT,1);
        b.setTextColor("C".equals(s)?red:navy);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(4),0,dp(4),0);
        GradientDrawable d=new GradientDrawable();
        d.setColor(Color.WHITE);
        d.setStroke(dp(1),Color.rgb(226,232,240));
        d.setCornerRadius(dp(14));
        b.setBackground(d);
        b.setElevation(dp(1));
        b.setStateListAnimator(null);
        return b;
    }
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
