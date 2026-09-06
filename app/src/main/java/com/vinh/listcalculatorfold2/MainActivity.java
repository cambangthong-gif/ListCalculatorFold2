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
        SHARE_BLANK_KEY="share_hide_blank", DENSITY_KEY="density_mode", SIDEBAR_MODE_KEY="sidebar_compact_mode", CASH_BASE_KEY="cash_base_amount", CASH_SCOPE_KEY="cash_scope";
    static final int REQ_BACKUP=501, REQ_RESTORE=502;
    final ArrayList<GroupModel> groups=new ArrayList<>();
    final ArrayList<TableModel> tables=new ArrayList<>();
    LinearLayout sidebar, gridHost, keypadHost;
    ScrollView gridScroll;
    RecyclerView gridRecycler;
    TextView pageIndicator, grandTotal, currentGroupTotal, cashRemainderView, compactTableTitle, compactGroupTitle, breadcrumbTitle;
    Button clearQtyBtn;
    View topInsetSpacer;
    Button tableBtn, undoBtn, quick1000;
    String selectedId=null;
    int activeRow=0;
    int pendingScrollRow=-1;
    int previousActiveRow=-1;
    String activeField="price";
    TableModel lastDeleted=null; int lastDeletedIndex=-1;
    String undoSnapshot=null, undoLabel="";
    int fastInputMode=0; // 0=List Calculator, 1=Enter/Tab tự chuyển ô, 2=thủ công
    boolean shareHideBlank=true;
    boolean sidebarCompactMode=false;
    double cashBaseAmount=0;
    int cashScope=0; // 0=bảng hiện tại, 1=nhóm hiện tại
    int densityMode=0; // 0=Auto, 1=Compact, 2=Comfortable
    int managerSortMode=0; // 0=thủ công,1=tên,2=mới nhất,3=cũ nhất
    View revealedSwipeRow=null;
    PopupWindow swipePreviewPopup=null, undoPopup=null;
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
    final HashSet<String> sidebarSelectedIds=new HashSet<>();
    HashSet<String> managerDragSelection=new HashSet<>();
    final Handler saveHandler=new Handler(Looper.getMainLooper());
    boolean saveScheduled=false;
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
        densityMode=p.getInt(DENSITY_KEY,0);
        sidebarCompactMode=p.getBoolean(SIDEBAR_MODE_KEY,false);
        cashBaseAmount=Double.longBitsToDouble(p.getLong(CASH_BASE_KEY,Double.doubleToLongBits(0)));
        cashScope=p.getInt(CASH_SCOPE_KEY,0);
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
                if(Math.abs(dx)>dp(26))showSwipePreview(dx<0);
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
            dismissSwipePreview();
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
            dismissSwipePreview();
            springTableBack();
        }

        return super.dispatchTouchEvent(e);
    }



    void showSwipePreview(boolean next){
        if(tables.size()<2)return;
        TableModel cur=selected();int idx=cur==null?0:tables.indexOf(cur);
        int target=next?idx+1:idx-1;
        if(target<0||target>=tables.size()){dismissSwipePreview();return;}
        String label=(next?"→ ":"← ")+tables.get(target).title;
        if(swipePreviewPopup!=null){
            TextView tv=(TextView)swipePreviewPopup.getContentView();tv.setText(label);return;
        }
        TextView tv=text(label,13,true);tv.setTextColor(Color.WHITE);tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14),dp(8),dp(14),dp(8));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.argb(220,30,64,100));bg.setCornerRadius(dp(18));tv.setBackground(bg);
        swipePreviewPopup=new PopupWindow(tv,-2,-2,false);
        swipePreviewPopup.setClippingEnabled(true);
        View anchor=getWindow().getDecorView();
        swipePreviewPopup.showAtLocation(anchor,Gravity.CENTER_VERTICAL|(next?Gravity.RIGHT:Gravity.LEFT),dp(18),0);
    }
    void dismissSwipePreview(){if(swipePreviewPopup!=null){swipePreviewPopup.dismiss();swipePreviewPopup=null;}}

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


    boolean isTinyPhone(int wDp){return wDp<380;}
    boolean isPhone(int wDp){return wDp<600;}
    boolean isSmallTablet(int wDp){return wDp>=600&&wDp<840;}
    boolean isTablet(int wDp){return wDp>=840;}
    boolean isLargeTablet(int wDp){return wDp>=1100;}

    int responsiveSideDp(int wDp){
        if(wDp<600)return 0;
        if(sidebarCompactMode){
            if(wDp<840)return 92;
            if(wDp<1100)return 104;
            return 116;
        }
        if(wDp<720)return Math.max(210,(int)(wDp*.31f));
        if(wDp<840)return Math.min(250,Math.max(220,(int)(wDp*.30f)));
        if(wDp<1100)return Math.min(290,Math.max(245,(int)(wDp*.27f)));
        return Math.min(340,Math.max(290,(int)(wDp*.25f)));
    }

    int responsiveKeypadDp(int wDp,int hDp,boolean landscape){
        if(landscape){
            if(wDp<600)return Math.max(132,Math.min(170,(int)(hDp*.43f)));
            return Math.max(170,Math.min(220,(int)(hDp*.36f)));
        }
        if(wDp<380)return Math.max(270,Math.min(315,(int)(hDp*.34f)));
        if(wDp<600)return Math.max(280,Math.min(330,(int)(hDp*.32f)));
        if(wDp<840)return Math.max(230,Math.min(270,(int)(hDp*.28f)));
        if(wDp<1100)return Math.max(215,Math.min(250,(int)(hDp*.25f)));
        return Math.max(205,Math.min(235,(int)(hDp*.23f)));
    }


    void setManagerButtonLabel(Button b,int wDp,boolean landscape){
        String count=String.valueOf(tables.size());
        if(wDp<380){
            b.setText("☰ QL");
            b.setTextSize(12);
        }else if(wDp<600 && landscape){
            b.setText("☰ QL");
            b.setTextSize(12);
        }else if(wDp<600){
            b.setText("☰ Quản lý");
            b.setTextSize(11);
        }else if(wDp<840){
            b.setText("☰ Bảng & nhóm");
            b.setTextSize(11);
        }else{
            b.setText("☰ Quản lý bảng/nhóm ("+count+")");
            b.setTextSize(12);
        }
        b.setContentDescription("Quản lý bảng và nhóm");
    }

    void setTopButtonLabel(Button b,String icon,String full,String shortText,int wDp,boolean landscape){
        if(wDp<380){
            b.setText(icon);
            b.setContentDescription(full);
            b.setTextSize(18);
        }else if(wDp<600 && landscape){
            b.setText(icon);
            b.setContentDescription(full);
            b.setTextSize(17);
        }else if(wDp<600){
            b.setText(shortText);
            b.setContentDescription(full);
        }else if(wDp<760){
            b.setText(icon+" "+shortText);
            b.setContentDescription(full);
        }else{
            b.setText(icon+" "+full);
            b.setContentDescription(full);
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
        compact=isPhone(swDp);
        boolean landscape=swDp>shDp;
        compactLandscape=compact && landscape;
        boolean tinyPhone=isTinyPhone(swDp);
        boolean smallTablet=isSmallTablet(swDp);
        boolean tablet=isTablet(swDp);
        boolean largeTablet=isLargeTablet(swDp);
        lastWidthBucket=compact?0:(smallTablet?1:(largeTablet?3:2));
        LinearLayout top=new LinearLayout(this);
        boolean innerNarrow=smallTablet;
        top.setOrientation(((compact && !compactLandscape)||(smallTablet && !landscape))?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6),dp(4),dp(6),dp(4));
        top.setBackgroundColor(Color.WHITE);
        top.setElevation(dp(2));

        tableBtn=topButton("");
        Button del=topButton("");
        undoBtn=topButton("");
        Button share=topButton("");
        quick1000=topButton(formatSample());
        Button addCalc=topButton("");
        Button addCancel=topButton("");

        setManagerButtonLabel(tableBtn,swDp,landscape);
        setTopButtonLabel(del,"⌫","Xóa","Xóa",swDp,landscape);
        setTopButtonLabel(undoBtn,"↶","Hoàn tác","Undo",swDp,landscape);
        if(swDp<380 || (swDp<600 && landscape)){
            share.setText("↗");
            share.setContentDescription("Chia sẻ ảnh bảng hoặc nhóm");
            share.setTextSize(17);
        }else if(swDp<600){
            share.setText("↗ Chia sẻ");
            share.setContentDescription("Chia sẻ ảnh bảng hoặc nhóm");
        }else{
            share.setText("↗ Chia sẻ ảnh");
            share.setContentDescription("Chia sẻ ảnh bảng hoặc nhóm");
        }
        setTopButtonLabel(addCalc,"＋","Bảng tính","+ Tính",swDp,landscape);
        setTopButtonLabel(addCancel,"＋","Bảng hủy","+ Hủy",swDp,landscape);
        if(swDp>=1000){
            quick1000.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_sort_by_size,0,0,0);
            quick1000.setCompoundDrawablePadding(dp(4));
        }

        tableBtn.setOnClickListener(v->{haptic(v);showTableManagerSheet();});
        del.setOnClickListener(v->{haptic(v);showMultiDeleteDialog();});
        undoBtn.setOnClickListener(v->{haptic(v);undoDelete();});
        share.setOnClickListener(v->{haptic(v);showShareChooser();});
        quick1000.setOnClickListener(v->{haptic(v);cycleNumberFormat();});
        addCalc.setOnClickListener(v->{haptic(v);addCalcTable(true);});
        addCancel.setOnClickListener(v->{haptic(v);addCancelTable(true);});

        if((compact && !compactLandscape)||(smallTablet && !landscape)){
            LinearLayout r1=new LinearLayout(this);r1.setGravity(Gravity.CENTER);
            LinearLayout r2=new LinearLayout(this);r2.setGravity(Gravity.CENTER);
            r1.addView(tableBtn,new LinearLayout.LayoutParams(0,dp(40),1.35f));
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
            top.addView(tableBtn,new LinearLayout.LayoutParams(0,h,1.32f));
            top.addView(del,new LinearLayout.LayoutParams(0,h,.92f));
            top.addView(undoBtn,new LinearLayout.LayoutParams(0,h,1.02f));
            top.addView(share,new LinearLayout.LayoutParams(0,h,.95f));
            top.addView(quick1000,new LinearLayout.LayoutParams(0,h,.78f));
            top.addView(addCalc,new LinearLayout.LayoutParams(0,h,1.18f));
            top.addView(addCancel,new LinearLayout.LayoutParams(0,h,1.24f));
        }

        int topHeight;
        boolean twoRowTop=(compact && !compactLandscape)||(smallTablet && !landscape);
        if(compactLandscape)topHeight=dp(44);
        else if(twoRowTop)topHeight=dp(tinyPhone?88:92);
        else topHeight=dp(tablet?54:52);
        root.addView(top,new LinearLayout.LayoutParams(-1,topHeight));

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
        int sideDp=responsiveSideDp(swDp);
        if(!compact) middle.addView(leftScroll,new LinearLayout.LayoutParams(dp(sideDp),-1));
        else sidebar=null;
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setBackgroundColor(Color.WHITE);
        if(!compact){
            breadcrumbTitle=text("",14,true);breadcrumbTitle.setTextColor(muted);
            breadcrumbTitle.setPadding(dp(12),0,dp(12),0);
            breadcrumbTitle.setGravity(Gravity.CENTER_VERTICAL);
            right.addView(breadcrumbTitle,new LinearLayout.LayoutParams(-1,dp(40)));
        }else breadcrumbTitle=null;
        gridHost=new LinearLayout(this);gridHost.setOrientation(LinearLayout.VERTICAL);gridHost.setBackgroundColor(Color.WHITE);gridHost.setElevation(dp(1));right.addView(gridHost,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(8),0,dp(10),0);footer.setBackgroundColor(Color.rgb(248,250,252));
        pageIndicator=text("1/1",13,false);
        grandTotal=text("0",compact?21:27,true);grandTotal.setTextColor(accent);grandTotal.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        int footerH=compactLandscape?40:(compact?52:(largeTablet?54:50));

        LinearLayout footerLeft=new LinearLayout(this);
        footerLeft.setGravity(Gravity.CENTER_VERTICAL);
        footerLeft.addView(pageIndicator,new LinearLayout.LayoutParams(0,dp(footerH),1));

        clearQtyBtn=smallActionButton("Xóa SL");
        clearQtyBtn.setTextColor(red);
        clearQtyBtn.setVisibility(View.GONE);
        clearQtyBtn.setOnClickListener(v->{haptic(v);confirmClearCurrentQuantities();});
        footerLeft.addView(clearQtyBtn,new LinearLayout.LayoutParams(dp(compactLandscape?72:86),dp(compactLandscape?32:38)));

        footer.addView(footerLeft,new LinearLayout.LayoutParams(0,dp(footerH),1.15f));

        currentGroupTotal=text("",compact?11:13,true);
        currentGroupTotal.setTextColor(Color.rgb(71,85,105));
        currentGroupTotal.setGravity(Gravity.CENTER);
        currentGroupTotal.setVisibility(View.GONE);
        currentGroupTotal.setPadding(dp(6),0,dp(6),0);
        GradientDrawable gtb=new GradientDrawable();
        gtb.setColor(Color.rgb(241,245,249));gtb.setCornerRadius(dp(12));
        currentGroupTotal.setBackground(gtb);
        currentGroupTotal.setOnClickListener(v->{
            TableModel ct=selected();
            if(ct==null||ct.groupId==null||ct.groupId.isEmpty())return;
            for(GroupModel g:groups)if(ct.groupId.equals(g.id)){haptic(v);showGroupStats(g);break;}
        });
        footer.addView(currentGroupTotal,new LinearLayout.LayoutParams(0,dp(compact?40:42),1.15f));

        cashRemainderView=text("💵 Còn lại",compact?10:12,true);
        cashRemainderView.setGravity(Gravity.CENTER);
        cashRemainderView.setTextColor(Color.rgb(22,101,52));
        cashRemainderView.setPadding(dp(4),0,dp(4),0);
        GradientDrawable cbg=new GradientDrawable();
        cbg.setColor(Color.rgb(240,253,244));
        cbg.setStroke(dp(1),Color.rgb(187,247,208));
        cbg.setCornerRadius(dp(12));
        cashRemainderView.setBackground(cbg);
        cashRemainderView.setOnClickListener(v->{haptic(v);showCashRemainderDialog();});
        footer.addView(cashRemainderView,new LinearLayout.LayoutParams(0,dp(compact?40:42),compact?1.05f:1.2f));

        footer.addView(grandTotal,new LinearLayout.LayoutParams(0,dp(footerH),compact?1.7f:2.0f));
        right.addView(footer);
        middle.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(middle,new LinearLayout.LayoutParams(-1,0,1));
        keypadHost=new LinearLayout(this);keypadHost.setOrientation(LinearLayout.HORIZONTAL);
        keypadHost.setPadding(dp(tinyPhone?1:3),0,dp(tinyPhone?1:3),dp(4));
        keypadHost.setBackgroundColor(Color.rgb(241,245,249));
        int keypadDp=responsiveKeypadDp(swDp,shDp,landscape);
        root.addView(keypadHost,new LinearLayout.LayoutParams(-1,dp(keypadDp)));
        setContentView(root);

        addCalc.setOnClickListener(v->{haptic(v);addCalcTable(true);});
        addCancel.setOnClickListener(v->{haptic(v);addCancelTable(true);}); del.setOnClickListener(v->{haptic(v);showMultiDeleteDialog();}); undoBtn.setOnClickListener(v->{haptic(v);undoDelete();}); share.setOnClickListener(v->{haptic(v);showShareChooser();}); quick1000.setOnClickListener(v->{haptic(v);cycleNumberFormat();}); tableBtn.setOnClickListener(v->{haptic(v);showTableManagerSheet();});
        renderAll();
    }

    void renderAll(){
        if(selected()==null&&!tables.isEmpty())selectedId=tables.get(0).id;
        int rw=getResources().getConfiguration().screenWidthDp;
        boolean rland=getResources().getConfiguration().screenWidthDp>getResources().getConfiguration().screenHeightDp;
        setManagerButtonLabel(tableBtn,rw,rland);
        quick1000.setText(formatSample());
        undoBtn.setEnabled(undoSnapshot!=null || lastDeleted!=null);
        updateCompactCurrentHeader();
        if(breadcrumbTitle!=null){
            TableModel bt=selected();
            String gn=bt==null?"":groupNameFor(bt.groupId);
            breadcrumbTitle.setText(bt==null?"":((gn.isEmpty()?"Chưa nhóm":gn)+"  ›  "+bt.title+(bt.locked?"  🔒":"")));
        }
        if(currentGroupTotal!=null){
            TableModel gt=selected();
            String gn=gt==null?"":groupNameFor(gt.groupId);
            if(gt!=null&&!gn.isEmpty()){
                currentGroupTotal.setText("Tổng nhóm\n"+fmt(groupTotal(gt.groupId)));
                currentGroupTotal.setVisibility(View.VISIBLE);
            }else currentGroupTotal.setVisibility(View.GONE);
        }
        updateCashRemainderView();
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
        String groupName=groupNameFor(t.groupId);
        compactTableTitle.setText(t.title);
        compactGroupTitle.setText((t.locked?"🔒  ":"")+(groupName.isEmpty()?"Chưa nhóm":groupName));
    }

    String groupNameFor(String gid){
        if(gid==null||gid.isEmpty())return "";
        for(GroupModel g:groups)if(gid.equals(g.id))return g.name;
        return "";
    }


    ArrayList<GroupModel> orderedGroups(){
        ArrayList<GroupModel> a=new ArrayList<>(groups);
        Collections.sort(a,(x,y)->{
            if(x.pinned!=y.pinned)return x.pinned?-1:1;
            return Integer.compare(groups.indexOf(x),groups.indexOf(y));
        });
        return a;
    }

    void togglePinGroup(GroupModel g){
        if(g==null)return;
        g.pinned=!g.pinned;
        saveNow();
        renderAll();
        Toast.makeText(this,g.pinned?"Đã ghim nhóm":"Đã bỏ ghim nhóm",Toast.LENGTH_SHORT).show();
    }


    void toggleSidebarMode(){
        sidebarCompactMode=!sidebarCompactMode;
        saveUiState();
        buildScreen();
    }

    void addSidebarModeButton(){
        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4),dp(3),dp(4),dp(3));
        Button toggle=smallActionButton(sidebarCompactMode?"▸ Rộng":"◂ Gọn");
        toggle.setOnClickListener(v->{haptic(v);toggleSidebarMode();});
        TextView label=text(sidebarCompactMode?"Bảng":"Danh sách bảng",11,true);
        label.setTextColor(muted);
        label.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(label,new LinearLayout.LayoutParams(0,dp(34),1));
        bar.addView(toggle,new LinearLayout.LayoutParams(dp(sidebarCompactMode?58:68),dp(32)));
        sidebar.addView(bar,new LinearLayout.LayoutParams(-1,dp(38)));
    }

    void renderSidebar(){
        if(sidebar==null)return;
        revealedSwipeRow=null;
        sidebar.removeAllViews();
        addSidebarModeButton();
        addPinnedDropZone();
        if(!sidebarSelectedIds.isEmpty())addSidebarSelectionBar();
        for(GroupModel g:orderedGroups())if(g.pinned)addSidebarSection(g.id,g);
        addSidebarSection(UNGROUPED,null);
        for(GroupModel g:orderedGroups())if(!g.pinned)addSidebarSection(g.id,g);
        sidebar.setOnDragListener((v,e)->true);
    }



    void addPinnedDropZone(){
        TextView pinZone=text("📌 Ghim nhóm",10,true);
        pinZone.setTextColor(muted);
        pinZone.setGravity(Gravity.CENTER);
        pinZone.setPadding(dp(6),dp(4),dp(6),dp(4));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(248,250,252));bg.setStroke(dp(1),rule);bg.setCornerRadius(dp(10));pinZone.setBackground(bg);
        pinZone.setOnDragListener((v,e)->{
            if(e.getAction()==DragEvent.ACTION_DRAG_ENTERED){v.setAlpha(.55f);return true;}
            if(e.getAction()==DragEvent.ACTION_DRAG_EXITED){v.setAlpha(1f);return true;}
            if(e.getAction()==DragEvent.ACTION_DROP){
                v.setAlpha(1f);
                Object st=e.getLocalState();
                if(st instanceof String && ((String)st).startsWith("GROUP:")){
                    String id=((String)st).substring(6);
                    GroupModel moving=null;for(GroupModel g:groups)if(id.equals(g.id)){moving=g;break;}
                    if(moving!=null){
                        pushUndo("Ghim nhóm");
                        moving.pinned=true;
                        groups.remove(moving);groups.add(0,moving);
                        saveNow();renderAll();
                    }
                }
                return true;
            }
            if(e.getAction()==DragEvent.ACTION_DRAG_ENDED)v.setAlpha(1f);
            return true;
        });
        sidebar.addView(pinZone,new LinearLayout.LayoutParams(-1,dp(28)));
    }

    void addSidebarSelectionBar(){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(6),dp(5),dp(6),dp(5));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(239,246,255));bg.setCornerRadius(dp(12));bar.setBackground(bg);
        TextView count=text(sidebarSelectedIds.size()+" đã chọn",12,true);count.setTextColor(accent);
        Button group=smallActionButton("+ Nhóm");Button move=smallActionButton("Chuyển");Button clear=smallActionButton("Bỏ chọn");
        bar.addView(count,new LinearLayout.LayoutParams(0,dp(42),1));
        bar.addView(group,new LinearLayout.LayoutParams(dp(66),dp(38)));
        bar.addView(move,new LinearLayout.LayoutParams(dp(66),dp(38)));
        bar.addView(clear,new LinearLayout.LayoutParams(dp(68),dp(38)));
        group.setOnClickListener(v->{haptic(v);createGroupFromSidebarSelection();});
        move.setOnClickListener(v->{haptic(v);moveSidebarSelectionDialog();});
        clear.setOnClickListener(v->{sidebarSelectedIds.clear();renderSidebar();});
        sidebar.addView(bar,new LinearLayout.LayoutParams(-1,dp(50)));
    }

    void createGroupFromSidebarSelection(){
        if(sidebarSelectedIds.isEmpty())return;
        EditText e=new EditText(this);e.setHint("Tên nhóm");e.setSingleLine();
        new AlertDialog.Builder(this).setTitle("Tạo nhóm từ "+sidebarSelectedIds.size()+" bảng").setView(padded(e))
            .setPositiveButton("Tạo",(d,w)->{
                String name=e.getText().toString().trim();if(name.isEmpty())return;
                pushUndo("Tạo nhóm");GroupModel g=new GroupModel();g.id=id();g.name=name;groups.add(g);
                for(TableModel t:tables)if(sidebarSelectedIds.contains(t.id))t.groupId=g.id;
                sidebarSelectedIds.clear();saveNow();renderAll();
            }).setNegativeButton("Hủy",null).show();
    }

    void moveSidebarSelectionDialog(){
        if(sidebarSelectedIds.isEmpty())return;
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");for(GroupModel g:groups)names.add(g.name);
        new AlertDialog.Builder(this).setTitle("Chuyển "+sidebarSelectedIds.size()+" bảng").setItems(names.toArray(new String[0]),(d,i)->{
            pushUndo("Chuyển nhiều bảng");String gid=i==0?UNGROUPED:groups.get(i-1).id;
            for(TableModel t:tables)if(sidebarSelectedIds.contains(t.id))t.groupId=gid;
            sidebarSelectedIds.clear();saveNow();renderAll();
        }).show();
    }

    void moveSidebarSelectionToGroup(String gid){
        if(sidebarSelectedIds.isEmpty())return;
        pushUndo("Kéo nhiều bảng vào nhóm");
        for(TableModel t:tables)if(sidebarSelectedIds.contains(t.id))t.groupId=gid;
        sidebarSelectedIds.clear();saveNow();renderAll();
    }

    void addSidebarSection(String gid,GroupModel group){
        if(group!=null){
            LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);gh.setPadding(dp(7),dp(5),dp(5),dp(5));gh.setBackgroundColor(groupBg);
            int gw=getResources().getConfiguration().screenWidthDp;
            TextView n=text(sidebarCompactMode
                    ?((group.pinned?"📌 ":"")+(collapsedGroups.contains(gid)?"▸ ":"▾ ")+shortSidebarTitle(group.name))
                    :((group.pinned?"📌 ":"")+(collapsedGroups.contains(gid)?"▸ ":"▾ ")+group.name),
                    sidebarCompactMode?10:(compact?12:(gw>=1100?15:14)),true);
            TextView sum=text(sidebarCompactMode?"":fmt(groupTotal(gid)),sidebarCompactMode?9:(compact?11:13),true);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);sum.setTextColor(accent);gh.addView(n,w(0,dp(38),1));gh.addView(sum,w(0,dp(38),0.8f));gh.setTag("GROUP:"+gid);
            gh.setOnDragListener((v,e)->{
                int a=e.getAction();
                if(a==DragEvent.ACTION_DRAG_ENTERED){v.setAlpha(.65f);return true;}
                if(a==DragEvent.ACTION_DRAG_EXITED){v.setAlpha(1f);return true;}
                if(a==DragEvent.ACTION_DRAG_ENDED){v.setAlpha(1f);return true;}
                if(a!=DragEvent.ACTION_DROP)return true;

                v.setAlpha(1f);
                Object st=e.getLocalState();
                if(!(st instanceof String))return true;
                String state=(String)st;

                if(state.startsWith("GROUP:")){
                    reorderGroup(state.substring(6),group.id);
                    return true;
                }
                if(state.startsWith("MULTI:")){
                    moveSidebarSelectionToGroup(gid);
                    return true;
                }

                TableModel moved=findTable(state);
                if(moved!=null){
                    pushUndo("Kéo bảng vào nhóm");
                    tables.remove(moved);moved.groupId=gid;tables.add(moved);
                    saveNow();renderAll();
                }
                return true;
            });
            // Không dùng vuốt/xóa nhanh trên tiêu đề nhóm ở sidebar để tránh thao tác nhầm.
            // Chạm nhóm chỉ thu gọn/mở rộng; quản lý nhóm qua màn Quản lý bảng & nhóm.
            gh.setOnClickListener(v->{
                if(collapsedGroups.contains(group.id))collapsedGroups.remove(group.id);
                else collapsedGroups.add(group.id);
                saveUiState();renderSidebar();
            });
            sidebar.addView(gh);
        }
        if(group!=null && collapsedGroups.contains(gid))return;
        ArrayList<TableModel> list=inGroup(gid); for(TableModel t:list)sidebar.addView(sidebarItem(t,gid));
    }


    String shortSidebarTitle(String s){
        if(s==null||s.isEmpty())return "Bảng";
        s=s.trim();
        if(s.length()<=8)return s;
        String[] p=s.split("\\s+");
        if(p.length>=2){
            String last=p[p.length-1];
            if(last.length()<=5)return p[0].substring(0,Math.min(1,p[0].length())).toUpperCase(Locale.getDefault())+"."+last;
        }
        return s.substring(0,Math.min(7,s.length()))+"…";
    }

    View sidebarItem(TableModel t,String gid){
        LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.HORIZONTAL);item.setTag(t.id);item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(6),dp(4),dp(4),dp(4));GradientDrawable itemBg=new GradientDrawable();
        itemBg.setColor(t.id.equals(selectedId)?Color.rgb(232,242,255):Color.TRANSPARENT);
        itemBg.setCornerRadius(dp(12));
        item.setBackground(itemBg);

        View typeStripe=new View(this);
        GradientDrawable stripeBg=new GradientDrawable();
        stripeBg.setColor("cancel".equals(t.type)?Color.rgb(245,158,11):accent);
        stripeBg.setCornerRadius(dp(3));typeStripe.setBackground(stripeBg);
        CheckBox pick=new CheckBox(this);pick.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        pick.setChecked(sidebarSelectedIds.contains(t.id));
        pick.setAlpha(sidebarSelectedIds.isEmpty()?0.72f:1f);
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(4),0,dp(2),0);
        int sideW=getResources().getConfiguration().screenWidthDp;
        TextView title=text(t.title,sideW>=1100?16:(compact?14:15),true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(t.id.equals(selectedId)?accent:ink);
        String groupBadge=groupNameFor(t.groupId);
        TextView meta=text((t.locked?"🔒 • ":"")+t.dataRowCount()+" dòng • "+fmt(t.total())+(groupBadge.isEmpty()?"":" • "+groupBadge),compact?10:11,false);
        meta.setSingleLine(true);meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setTextColor(muted);
        info.addView(title);info.addView(meta);

        TextView drag=text("≡",22,true);drag.setGravity(Gravity.CENTER);
        drag.setTextColor(t.id.equals(selectedId)?accent:muted);
        TextView more=text("⋮",24,true);more.setGravity(Gravity.CENTER);more.setTextColor(muted);
        item.addView(typeStripe,new LinearLayout.LayoutParams(dp(4),dp(sidebarCompactMode?48:46)));
        if(!sidebarCompactMode)item.addView(pick,new LinearLayout.LayoutParams(dp(30),dp(56)));
        item.addView(info,new LinearLayout.LayoutParams(0,dp(sidebarCompactMode?54:58),1));
        item.addView(drag,new LinearLayout.LayoutParams(dp(sidebarCompactMode?28:34),dp(sidebarCompactMode?54:58)));
        if(!sidebarCompactMode)item.addView(more,new LinearLayout.LayoutParams(dp(34),dp(58)));

        pick.setOnCheckedChangeListener((b,on)->{if(on)sidebarSelectedIds.add(t.id);else sidebarSelectedIds.remove(t.id);renderSidebar();});
        View.OnClickListener select=v->{selectedId=t.id;activeRow=0;activeField="cancel".equals(t.type)?"qty":"price";explicitCellSelection=false;renderAll();};
        info.setOnClickListener(select);
        title.setOnClickListener(select);

        // Nhấn giữ bảng ở màn trong = vào chế độ chọn với bảng này được chọn sẵn.
        info.setOnLongClickListener(v->{haptic(v);if(sidebarSelectedIds.contains(t.id))sidebarSelectedIds.remove(t.id);else sidebarSelectedIds.add(t.id);renderSidebar();return true;});

        more.setOnClickListener(v->{haptic(v);selectedId=t.id;showQuickTableActions(t);});
        more.setOnLongClickListener(v->{haptic(v);selectedId=t.id;renameCurrent();return true;});

        // Chỉ kéo bằng tay cầm ≡.
        drag.setOnLongClickListener(v->{
            haptic(v);
            if(!sidebarSelectedIds.isEmpty()){
                sidebarSelectedIds.add(t.id);
                if(sidebarSelectedIds.size()>1){
                    ClipData cd=ClipData.newPlainText("tables",String.valueOf(sidebarSelectedIds.size()));
                    v.startDragAndDrop(cd,new View.DragShadowBuilder(item),"MULTI:"+t.id,0);
                }else{
                    ClipData cd=ClipData.newPlainText("table",t.id);
                    v.startDragAndDrop(cd,new View.DragShadowBuilder(item),t.id,0);
                }
            }else{
                ClipData cd=ClipData.newPlainText("table",t.id);
                v.startDragAndDrop(cd,new View.DragShadowBuilder(item),t.id,0);
            }
            return true;
        });

        item.setOnDragListener((v,e)->{
            if(e.getAction()==DragEvent.ACTION_DROP){
                String movingId=(String)e.getLocalState();TableModel moving=findTable(movingId);
                if(moving==null||moving==t)return true;
                tables.remove(moving);moving.groupId=gid;int idx=tables.indexOf(t);
                tables.add(Math.max(0,idx),moving);save();renderAll();return true;
            }
            return true;
        });
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(3),dp(4),dp(3),dp(4));
        int swipeBtn=dp(getResources().getConfiguration().screenWidthDp>=840?46:42);
        Button rename=sidebarIconActionButton("✎",false,"Đổi tên");
        Button move=sidebarIconActionButton("↪",false,"Chuyển nhóm");
        Button del=sidebarIconActionButton("🗑",true,"Xóa");
        LinearLayout.LayoutParams sbp=new LinearLayout.LayoutParams(0,dp(50),1);
        sbp.setMargins(dp(2),0,dp(2),0);
        actions.addView(rename,sbp);
        LinearLayout.LayoutParams sbp2=new LinearLayout.LayoutParams(0,dp(50),1);sbp2.setMargins(dp(2),0,dp(2),0);actions.addView(move,sbp2);
        LinearLayout.LayoutParams sbp3=new LinearLayout.LayoutParams(0,dp(50),1);
        sbp3.setMargins(dp(2),0,dp(0),0);
        actions.addView(del,sbp3);

        rename.setOnClickListener(v->{selectedId=t.id;closeRevealedSwipe();renameCurrent();});
        move.setOnClickListener(v->{selectedId=t.id;closeRevealedSwipe();moveCurrentGroup();});
        del.setOnClickListener(v->{selectedId=t.id;closeRevealedSwipe();deleteCurrent();});

        // Bỏ vuốt hành động ở menu trái màn trong để tránh lỗi hiển thị.
        // Đổi tên / chuyển nhóm / xóa dùng nút ⋮ hoặc màn Quản lý bảng & nhóm.
        return item;
    }


    int dataRowDp(){
        int w=getResources().getConfiguration().screenWidthDp;
        if(densityMode==1)return w<380?40:(w<600?42:(w<840?44:46));
        if(densityMode==2)return w<380?54:(w<600?58:(w<840?60:62));
        return w<380?46:(w<600?50:(w<840?52:54));
    }

    int headerRowDp(){
        int w=getResources().getConfiguration().screenWidthDp;
        if(densityMode==1)return w<380?44:(w<600?46:(w<840?48:50));
        if(densityMode==2)return w<380?58:(w<600?60:(w<840?62:64));
        return w<380?48:(w<600?52:(w<840?54:56));
    }

    TextView emptyHint(String s){
        TextView v=text(s,12,false);v.setTextColor(muted);v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(248,250,252));return v;
    }

    void fillCell(TextView v,int color){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setStroke(dp(1),rule);v.setBackground(d);
    }

    void renderGrid(){
        gridHost.removeAllViews();gridScroll=null;gridRecycler=null;
        previousActiveRow=activeRow;
        TableModel t=selected();if(t==null)return;
        if("cancel".equals(t.type))renderCancelGrid(t);else renderCalcGrid(t);
        pageIndicator.setText((tables.indexOf(t)+1)+"/"+tables.size());
        grandTotal.setText(fmt(t.total()));
        if(clearQtyBtn!=null)clearQtyBtn.setVisibility("cancel".equals(t.type)?View.VISIBLE:View.GONE);
        scrollActiveRowIntoView();
    }

    void renderCalcGrid(TableModel t){
        gridHost.setPadding(0,0,0,0);
        LinearLayout head=gridRow();
        int hh=dp(headerRowDp());
        head.setLayoutParams(new LinearLayout.LayoutParams(-1,hh));
        head.addView(cell("STT",14,false,Gravity.CENTER),w(0,ViewGroup.LayoutParams.MATCH_PARENT,0.45f));
        head.addView(cell("Đơn giá",14,false,Gravity.CENTER),w(0,ViewGroup.LayoutParams.MATCH_PARENT,2));
        head.addView(cell("SL",14,false,Gravity.CENTER),w(0,ViewGroup.LayoutParams.MATCH_PARENT,1));
        head.addView(cell("Thành tiền",14,false,Gravity.END|Gravity.CENTER_VERTICAL),w(0,ViewGroup.LayoutParams.MATCH_PARENT,2));
        gridHost.addView(head);
        if(t.dataRowCount()==0)gridHost.addView(emptyHint("Nhập Đơn giá bên trái và Số lượng bên phải"),new LinearLayout.LayoutParams(-1,dp(34)));
        ensureBlankCalc(t);
        gridRecycler=new RecyclerView(this);
        gridRecycler.setLayoutManager(new LinearLayoutManager(this));
        gridRecycler.setHasFixedSize(true);
        gridRecycler.setItemViewCacheSize(18);
        gridRecycler.getRecycledViewPool().setMaxRecycledViews(0,24);
        gridRecycler.setItemAnimator(null);
        gridRecycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        gridRecycler.setAdapter(new CalcAdapter(t));
        gridHost.addView(gridRecycler,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
    }


    long cancelTotalQty(TableModel t){
        long sum=0;for(CancelRow r:t.cancelRows)sum+=Math.max(0,r.qty);return sum;
    }
    int cancelNamedAgents(TableModel t){
        int n=0;for(CancelRow r:t.cancelRows)if(r.agent!=null&&!r.agent.trim().isEmpty())n++;return n;
    }

    LinearLayout cancelSummaryCard(TableModel t){
        LinearLayout card=new LinearLayout(this);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10),dp(5),dp(10),dp(5));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(255,247,237));bg.setCornerRadius(dp(12));bg.setStroke(dp(1),Color.rgb(254,215,170));
        card.setBackground(bg);
        TextView left=text("🎟  "+t.title,13,true);left.setTextColor(ink);
        TextView mid=text(cancelNamedAgents(t)+" đại lý",12,false);mid.setTextColor(muted);mid.setGravity(Gravity.CENTER);
        TextView right=text("SL "+fmt(cancelTotalQty(t)),14,true);right.setTextColor(Color.rgb(194,65,12));right.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        card.addView(left,new LinearLayout.LayoutParams(0,dp(34),1.35f));
        card.addView(mid,new LinearLayout.LayoutParams(0,dp(34),.8f));
        card.addView(right,new LinearLayout.LayoutParams(0,dp(34),1f));
        return card;
    }

    void renderCancelGrid(TableModel t){
        gridHost.setPadding(dp(4),dp(4),dp(4),0);
        gridHost.addView(cancelSummaryCard(t),new LinearLayout.LayoutParams(-1,dp(44)));

        LinearLayout head=gridRow();
        TextView hs=cell("#",13,true,Gravity.CENTER);
        TextView ha=cell("Tên đại lý",14,true,Gravity.START|Gravity.CENTER_VERTICAL);
        TextView hq=cell("Số lượng",14,true,Gravity.END|Gravity.CENTER_VERTICAL);
        fillCell(hs,Color.rgb(255,251,235));fillCell(ha,Color.rgb(255,251,235));fillCell(hq,Color.rgb(255,251,235));
        int hh=dp(headerRowDp());
        head.setLayoutParams(new LinearLayout.LayoutParams(-1,hh));
        head.addView(hs,w(0,ViewGroup.LayoutParams.MATCH_PARENT,0.38f));
        head.addView(ha,w(0,ViewGroup.LayoutParams.MATCH_PARENT,3.5f));
        head.addView(hq,w(0,ViewGroup.LayoutParams.MATCH_PARENT,1.65f));
        gridHost.addView(head);

        if(t.dataRowCount()==0)gridHost.addView(emptyHint("Chạm Tên đại lý để nhập • bàn phím bên phải nhập Số lượng"),
                new LinearLayout.LayoutParams(-1,dp(32)));

        ensureBlankCancel(t);
        gridRecycler=new RecyclerView(this);
        gridRecycler.setLayoutManager(new LinearLayoutManager(this));
        gridRecycler.setHasFixedSize(true);
        gridRecycler.setItemViewCacheSize(18);
        gridRecycler.getRecycledViewPool().setMaxRecycledViews(0,24);
        gridRecycler.setItemAnimator(null);
        gridRecycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        gridRecycler.setAdapter(new CancelAdapter(t));
        gridHost.addView(gridRecycler,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
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
            int rowH=dp(dataRowDp());
            rr.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,rowH));
            TextView st=cell("",14,false,Gravity.CENTER);
            TextView p=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            TextView q=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            TextView total=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            rr.addView(st,w(0,ViewGroup.LayoutParams.MATCH_PARENT,0.45f));
            rr.addView(p,w(0,ViewGroup.LayoutParams.MATCH_PARENT,2));
            rr.addView(q,w(0,ViewGroup.LayoutParams.MATCH_PARENT,1));
            rr.addView(total,w(0,ViewGroup.LayoutParams.MATCH_PARENT,2));
            return new H(rr,st,p,q,total);
        }
        @Override public void onBindViewHolder(H h,int row){
            CalcRow r=row<t.calcRows.size()?t.calcRows.get(row):null;
            h.st.setText((r!=null&&!r.blank()?"≡ ":"")+String.valueOf(row+1));
            h.p.setText(r==null||r.price==0?"":fmt(r.price));
            h.q.setText(r==null||r.qty==0?"":fmt(r.qty));
            h.total.setText(r==null||r.price==0||r.qty==0?"":fmt(r.price*r.qty));
            resetCell(h.st);resetCell(h.p);resetCell(h.q);resetCell(h.total);
            int rowBg=Color.WHITE;
            if(r!=null && r.price!=0 && r.qty==0)rowBg=Color.rgb(255,251,235);
            if(row==activeRow)rowBg=Color.rgb(239,246,255);
            fillCell(h.st,rowBg);fillCell(h.p,rowBg);fillCell(h.q,rowBg);fillCell(h.total,rowBg);
            if(row==activeRow&&"price".equals(activeField))markActive(h.p);
            if(row==activeRow&&"qty".equals(activeField))markActive(h.q);
            h.st.setOnClickListener(v->{
                if(r!=null&&!r.blank()){
                    haptic(v);
                    showMoveRowDialog(t,row);
                }else{
                    previousActiveRow=activeRow;activeRow=row;pendingScrollRow=row;explicitCellSelection=false;
                    saveUiState();notifyDataSetChanged();previousActiveRow=activeRow;renderKeypads();
                }
            });
            h.p.setOnClickListener(v->{previousActiveRow=activeRow;activeRow=row;pendingScrollRow=row;activeField="price";explicitCellSelection=true;ensureRow(t,row);saveUiState();notifyDataSetChanged();previousActiveRow=activeRow;renderKeypads();});
            h.q.setOnClickListener(v->{previousActiveRow=activeRow;activeRow=row;pendingScrollRow=row;activeField="qty";explicitCellSelection=true;ensureRow(t,row);saveUiState();notifyDataSetChanged();previousActiveRow=activeRow;renderKeypads();});
            View.OnLongClickListener deleteRowsLong=v->{
                haptic(v);
                showRowDeleteDialog(t,row);
                return true;
            };
            h.row.setOnLongClickListener(deleteRowsLong);
            h.st.setOnLongClickListener(v->{if(r!=null&&!r.blank()){haptic(v);showMoveRowDialog(t,row);}return true;});
            h.p.setOnLongClickListener(deleteRowsLong);
            h.q.setOnLongClickListener(deleteRowsLong);
            h.total.setOnLongClickListener(deleteRowsLong);
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
            int rowH=dp(dataRowDp());
            rr.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,rowH));
            TextView st=cell("",14,false,Gravity.CENTER);
            TextView a=cell("",16,false,Gravity.START|Gravity.CENTER_VERTICAL);
            TextView q=cell("",15,false,Gravity.END|Gravity.CENTER_VERTICAL);
            rr.addView(st,w(0,ViewGroup.LayoutParams.MATCH_PARENT,0.38f));
            rr.addView(a,w(0,ViewGroup.LayoutParams.MATCH_PARENT,3.5f));
            rr.addView(q,w(0,ViewGroup.LayoutParams.MATCH_PARENT,1.65f));
            return new H(rr,st,a,q);
        }
        @Override public void onBindViewHolder(H h,int row){
            CancelRow r=row<t.cancelRows.size()?t.cancelRows.get(row):null;
            h.st.setText((r!=null&&!r.blank()?"≡ ":"")+String.valueOf(row+1));
            h.a.setText(r==null||r.agent==null||r.agent.isEmpty()?(row==activeRow?"Chạm để nhập đại lý":""):r.agent);
            h.a.setTextColor(r==null||r.agent==null||r.agent.isEmpty()?muted:ink);
            h.q.setText(r==null||r.qty==0?"":fmt(r.qty));
            resetCell(h.st);resetCell(h.a);resetCell(h.q);
            int rowBg=(row==activeRow)?Color.rgb(255,247,237):(row%2==0?Color.WHITE:Color.rgb(252,252,253));
            fillCell(h.st,rowBg);fillCell(h.a,rowBg);fillCell(h.q,rowBg);
            if(row==activeRow)markActive(h.q);
            h.st.setOnClickListener(v->{
                if(r!=null&&!r.blank()){
                    haptic(v);showMoveRowDialog(t,row);
                }else{
                    previousActiveRow=activeRow;activeRow=row;pendingScrollRow=row;activeField="qty";
                    explicitCellSelection=false;saveUiState();notifyDataSetChanged();previousActiveRow=activeRow;renderKeypads();
                }
            });
            h.a.setOnClickListener(v->{ensureCancelRow(t,row);editAgent(t,row);});
            h.q.setOnClickListener(v->{previousActiveRow=activeRow;activeRow=row;pendingScrollRow=row;activeField="qty";explicitCellSelection=true;ensureCancelRow(t,row);saveUiState();notifyDataSetChanged();previousActiveRow=activeRow;renderKeypads();});

            View.OnLongClickListener deleteRowsLong=v->{
                haptic(v);
                showRowDeleteDialog(t,row);
                return true;
            };
            h.row.setOnLongClickListener(deleteRowsLong);
            h.st.setOnLongClickListener(v->{if(r!=null&&!r.blank()){haptic(v);showMoveRowDialog(t,row);}return true;});
            h.a.setOnLongClickListener(deleteRowsLong);
            h.q.setOnLongClickListener(deleteRowsLong);
        }
        @Override public int getItemCount(){return Math.max(8,t.cancelRows.size());}
    }

    void renderKeypads(){
        keypadHost.removeAllViews();
        TableModel t=selected();if(t==null)return;
        if("cancel".equals(t.type)){
            keypadHost.addView(buildCancelQuickPanel(t),w(0,-1,1));
            keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));
        }else{
            keypadHost.addView(buildPad("Đơn giá","price"),w(0,-1,1));
            keypadHost.addView(buildPad("Số lượng","qty"),w(0,-1,1));
        }
    }

    LinearLayout buildCancelQuickPanel(TableModel t){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10),dp(8),dp(8),dp(6));box.setGravity(Gravity.TOP);

        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon=text("🎟",22,false);icon.setGravity(Gravity.CENTER);
        LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
        TextView title=text(t.title,compact?15:17,true);title.setTextColor(ink);
        String gn=groupNameFor(t.groupId);TextView group=text(gn.isEmpty()?"Chưa nhóm":gn,11,false);group.setTextColor(muted);
        labels.addView(title);labels.addView(group);
        TextView total=text("SL "+fmt(cancelTotalQty(t)),compact?14:16,true);total.setTextColor(Color.rgb(194,65,12));total.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        header.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(44)));
        header.addView(labels,new LinearLayout.LayoutParams(0,dp(44),1));
        header.addView(total,new LinearLayout.LayoutParams(dp(100),dp(44)));
        box.addView(header);

        LinearLayout r1=new LinearLayout(this),r2=new LinearLayout(this);
        Button addAgent=smallActionButton("+ Đại lý");
        Button move=smallActionButton("↪ Nhóm");
        Button copy=smallActionButton("⧉ Copy");
        Button clear=smallActionButton("Xóa SL");clear.setTextColor(red);

        addAgent.setOnClickListener(v->{haptic(v);int row=Math.max(0,t.cancelRows.size()-1);ensureCancelRow(t,row);editAgent(t,row);});
        move.setOnClickListener(v->{haptic(v);moveCurrentGroup();});
        copy.setOnClickListener(v->{haptic(v);showCopyOptions(t);});
        clear.setOnClickListener(v->{haptic(v);confirmClearCurrentQuantities();});

        int bh=compactLandscape?38:46;
        r1.addView(addAgent,new LinearLayout.LayoutParams(0,dp(bh),1));
        r1.addView(move,new LinearLayout.LayoutParams(0,dp(bh),1));
        r2.addView(copy,new LinearLayout.LayoutParams(0,dp(bh),1));
        r2.addView(clear,new LinearLayout.LayoutParams(0,dp(bh),1));
        box.addView(r1,new LinearLayout.LayoutParams(-1,dp(bh+4)));
        box.addView(r2,new LinearLayout.LayoutParams(-1,dp(bh+4)));

        TextView tip=text("Tên đại lý: chạm trực tiếp trong bảng",10,false);tip.setTextColor(muted);tip.setGravity(Gravity.CENTER);
        box.addView(tip,new LinearLayout.LayoutParams(-1,dp(24)));
        return box;
    }

    LinearLayout buildPad(String label,String field){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(4),dp(4),dp(4),dp(2));TextView lab=text(label,compact?14:15,field.equals(activeField));lab.setTextColor(field.equals(activeField)?accent:muted);lab.setGravity(Gravity.CENTER);wrap.addView(lab,new LinearLayout.LayoutParams(-1,dp(compactLandscape?20:28)));String[][] keys={{"7","8","9"},{"4","5","6"},{"1","2","3"},{"⌫","0","C"}};for(String[] row:keys){LinearLayout rr=new LinearLayout(this);rr.setPadding(0,0,dp(3),dp(3));for(String k:row){Button b=keyButton(k);b.setOnClickListener(v->{haptic(v);handleKey(field,k);});rr.addView(b,w(0,-1,1));}wrap.addView(rr,new LinearLayout.LayoutParams(-1,0,1));}return wrap;
    }

    void refreshActiveRowHighlight(){
        if(gridRecycler==null||gridRecycler.getAdapter()==null)return;
        RecyclerView.Adapter a=gridRecycler.getAdapter();
        int count=a.getItemCount();
        if(previousActiveRow>=0 && previousActiveRow<count && previousActiveRow!=activeRow){
            a.notifyItemChanged(previousActiveRow);
        }
        if(activeRow>=0 && activeRow<count){
            a.notifyItemChanged(activeRow);
        }
        previousActiveRow=activeRow;
    }

    void updateLiveTotals(TableModel t){
        if(t==null)return;
        if(grandTotal!=null)grandTotal.setText(fmt(t.total()));
        if(currentGroupTotal!=null){
            String gn=groupNameFor(t.groupId);
            if(t.groupId!=null&&!t.groupId.isEmpty()&&!gn.isEmpty()){
                currentGroupTotal.setText("Tổng nhóm\n"+fmt(groupTotal(t.groupId)));
                currentGroupTotal.setVisibility(View.VISIBLE);
            }else{
                currentGroupTotal.setVisibility(View.GONE);
            }
        }
        updateCashRemainderView();
    }

    double cashSubtractAmount(){
        TableModel t=selected();
        if(t==null)return 0;
        if(cashScope==1 && t.groupId!=null && !t.groupId.isEmpty())return groupTotal(t.groupId);
        return t.total();
    }

    void updateCashRemainderView(){
        if(cashRemainderView==null)return;
        if(cashBaseAmount<=0){
            cashRemainderView.setText(compact?"💵":"💵 Còn lại");
            cashRemainderView.setContentDescription("Tính tiền mặt còn lại");
            return;
        }
        double sub=cashSubtractAmount();
        double remain=cashBaseAmount-sub;
        String scope=(cashScope==1?"Nhóm":"Bảng");
        cashRemainderView.setText((compact?"💵 ":"Còn lại • "+scope+"\n")+fmt(remain));
        cashRemainderView.setContentDescription("Tiền mặt còn lại "+fmt(remain)+", trừ theo "+scope);
    }

    void showCashRemainderDialog(){
        TableModel t=selected();if(t==null)return;

        ScrollView scroll=new ScrollView(this);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16),dp(6),dp(16),dp(10));
        scroll.addView(box);

        // ===== KHỐI 1: TỔNG TIỀN BAN ĐẦU =====
        TextView step1=text("1. TỔNG TIỀN BAN ĐẦU",13,true);
        step1.setTextColor(accent);
        box.addView(step1,new LinearLayout.LayoutParams(-1,dp(30)));

        LinearLayout baseCard=new LinearLayout(this);
        baseCard.setOrientation(LinearLayout.VERTICAL);
        baseCard.setPadding(dp(12),dp(8),dp(12),dp(10));
        GradientDrawable baseBg=new GradientDrawable();
        baseBg.setColor(Color.rgb(248,250,252));
        baseBg.setStroke(dp(1),Color.rgb(226,232,240));
        baseBg.setCornerRadius(dp(14));
        baseCard.setBackground(baseBg);

        EditText amount=new EditText(this);
        amount.setHint("Nhập tổng tiền ban đầu");
        amount.setSingleLine();
        amount.setTextSize(25);
        amount.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if(cashBaseAmount>0)amount.setText(String.valueOf((long)Math.round(cashBaseAmount)));
        baseCard.addView(amount,new LinearLayout.LayoutParams(-1,dp(58)));

        LinearLayout baseActions=new LinearLayout(this);
        Button calcToggle=smallActionButton("🧮 Máy tính");
        Button clearAmount=smallActionButton("Xóa");
        clearAmount.setTextColor(red);
        baseActions.addView(calcToggle,new LinearLayout.LayoutParams(0,dp(42),1.35f));
        baseActions.addView(clearAmount,new LinearLayout.LayoutParams(0,dp(42),.75f));
        baseCard.addView(baseActions);
        box.addView(baseCard);

        // Máy tính phụ - ẩn mặc định, chỉ mở khi cần.
        LinearLayout calcPanel=new LinearLayout(this);
        calcPanel.setOrientation(LinearLayout.VERTICAL);
        calcPanel.setPadding(dp(10),dp(8),dp(10),dp(10));
        calcPanel.setVisibility(View.GONE);
        GradientDrawable calcBg=new GradientDrawable();
        calcBg.setColor(Color.rgb(255,255,255));
        calcBg.setStroke(dp(1),Color.rgb(203,213,225));
        calcBg.setCornerRadius(dp(14));
        calcPanel.setBackground(calcBg);

        TextView calcLabel=text("Máy tính tiền",12,true);
        calcLabel.setTextColor(muted);
        calcPanel.addView(calcLabel,new LinearLayout.LayoutParams(-1,dp(26)));

        TextView expression=text("",13,false);
        expression.setTextColor(muted);
        expression.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        calcPanel.addView(expression,new LinearLayout.LayoutParams(-1,dp(26)));

        TextView calcDisplay=text("0",25,true);
        calcDisplay.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        calcDisplay.setTextColor(ink);
        calcPanel.addView(calcDisplay,new LinearLayout.LayoutParams(-1,dp(52)));

        final double[] acc={0};
        final String[] op={""};
        final boolean[] startNew={false};
        final String[] expr={""};

        java.util.function.Consumer<String> calcKey=(String key)->{
            String cur=calcDisplay.getText().toString().replace(".","").replace(",","").trim();
            if(cur.isEmpty())cur="0";

            if("C".equals(key)){
                calcDisplay.setText("0");
                expression.setText("");
                acc[0]=0;op[0]="";startNew[0]=false;expr[0]="";
                return;
            }
            if("⌫".equals(key)){
                if(startNew[0])return;
                String s=cur;
                calcDisplay.setText(s.length()>1?s.substring(0,s.length()-1):"0");
                return;
            }

            boolean isOp="+".equals(key)||"−".equals(key)||"×".equals(key)||"÷".equals(key);
            if(isOp || "=".equals(key)){
                double current=parseNum(cur);

                if(!op[0].isEmpty()){
                    if("+".equals(op[0]))acc[0]+=current;
                    else if("−".equals(op[0]))acc[0]-=current;
                    else if("×".equals(op[0]))acc[0]*=current;
                    else if("÷".equals(op[0])){
                        if(current==0){
                            Toast.makeText(this,"Không thể chia cho 0",Toast.LENGTH_SHORT).show();
                            return;
                        }
                        acc[0]/=current;
                    }
                }else acc[0]=current;

                calcDisplay.setText(String.valueOf((long)Math.round(acc[0])));

                if("=".equals(key)){
                    String left=expr[0].isEmpty()?fmt(current):expr[0]+" "+fmt(current);
                    expression.setText(left+" =");
                    expr[0]="";op[0]="";startNew[0]=true;
                }else{
                    expr[0]=fmt(acc[0])+" "+key;
                    expression.setText(expr[0]);
                    op[0]=key;
                    startNew[0]=true;
                }
                return;
            }

            if(startNew[0] || "0".equals(cur)){
                calcDisplay.setText(key);
                startNew[0]=false;
            }else calcDisplay.setText(cur+key);
        };

        String[][] keys={
            {"7","8","9","÷"},
            {"4","5","6","×"},
            {"1","2","3","−"},
            {"C","0","⌫","+"}
        };
        for(String[] row:keys){
            LinearLayout r=new LinearLayout(this);
            for(String k:row){
                Button b=smallActionButton(k);
                b.setTextSize(("÷×−+".contains(k))?20:17);
                if("C".equals(k))b.setTextColor(red);
                b.setOnClickListener(v->{haptic(v);calcKey.accept(k);});
                r.addView(b,new LinearLayout.LayoutParams(0,dp(43),1));
            }
            calcPanel.addView(r,new LinearLayout.LayoutParams(-1,dp(45)));
        }

        LinearLayout calcBottom=new LinearLayout(this);
        Button equalBtn=smallActionButton("=");
        equalBtn.setTextSize(22);
        Button useCalc=smallActionButton("Dùng kết quả");
        useCalc.setTextColor(Color.rgb(22,101,52));
        useCalc.setTextSize(14);
        calcBottom.addView(equalBtn,new LinearLayout.LayoutParams(0,dp(46),.55f));
        calcBottom.addView(useCalc,new LinearLayout.LayoutParams(0,dp(46),1.45f));
        calcPanel.addView(calcBottom);

        equalBtn.setOnClickListener(v->{haptic(v);calcKey.accept("=");});
        useCalc.setOnClickListener(v->{
            haptic(v);
            String raw=calcDisplay.getText().toString().replace(".","").replace(",","");
            double value=parseNum(raw);
            if(value<=0){
                Toast.makeText(this,"Kết quả phải lớn hơn 0",Toast.LENGTH_SHORT).show();
                return;
            }
            amount.setText(String.valueOf((long)Math.round(value)));
            amount.setSelection(amount.getText().length());
            calcPanel.setVisibility(View.GONE);
            calcToggle.setText("🧮 Máy tính");
        });

        calcToggle.setOnClickListener(v->{
            haptic(v);
            boolean show=calcPanel.getVisibility()!=View.VISIBLE;
            calcPanel.setVisibility(show?View.VISIBLE:View.GONE);
            calcToggle.setText(show?"Ẩn máy tính":"🧮 Máy tính");
            if(show && cashBaseAmount>0 && "0".contentEquals(calcDisplay.getText())){
                calcDisplay.setText(String.valueOf((long)Math.round(cashBaseAmount)));
            }
        });
        clearAmount.setOnClickListener(v->{haptic(v);amount.setText("");});

        box.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(8)));
        box.addView(calcPanel);

        // ===== KHỐI 2: PHẠM VI TRỪ =====
        box.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(14)));
        TextView step2=text("2. TRỪ THEO",13,true);
        step2.setTextColor(accent);
        box.addView(step2,new LinearLayout.LayoutParams(-1,dp(30)));

        LinearLayout scopeCard=new LinearLayout(this);
        scopeCard.setOrientation(LinearLayout.VERTICAL);
        scopeCard.setPadding(dp(12),dp(6),dp(12),dp(8));
        GradientDrawable scopeBg=new GradientDrawable();
        scopeBg.setColor(Color.WHITE);
        scopeBg.setStroke(dp(1),Color.rgb(226,232,240));
        scopeBg.setCornerRadius(dp(14));
        scopeCard.setBackground(scopeBg);

        RadioGroup rg=new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton tableRadio=new RadioButton(this);tableRadio.setText("Bảng hiện tại");
        RadioButton groupRadio=new RadioButton(this);groupRadio.setText("Nhóm hiện tại");
        boolean hasGroup=t.groupId!=null&&!t.groupId.isEmpty()&&!groupNameFor(t.groupId).isEmpty();
        groupRadio.setEnabled(hasGroup);
        rg.addView(tableRadio,new RadioGroup.LayoutParams(0,dp(46),1));
        rg.addView(groupRadio,new RadioGroup.LayoutParams(0,dp(46),1));
        if(cashScope==1 && hasGroup)groupRadio.setChecked(true);else tableRadio.setChecked(true);
        scopeCard.addView(rg);

        TextView subtractInfo=text("",14,true);
        subtractInfo.setTextColor(ink);
        subtractInfo.setGravity(Gravity.CENTER_VERTICAL);
        scopeCard.addView(subtractInfo,new LinearLayout.LayoutParams(-1,dp(40)));
        box.addView(scopeCard);

        // ===== KHỐI 3: KẾT QUẢ =====
        box.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(14)));
        TextView step3=text("3. KẾT QUẢ",13,true);
        step3.setTextColor(accent);
        box.addView(step3,new LinearLayout.LayoutParams(-1,dp(30)));

        LinearLayout resultCard=new LinearLayout(this);
        resultCard.setOrientation(LinearLayout.VERTICAL);
        resultCard.setPadding(dp(14),dp(10),dp(14),dp(12));
        GradientDrawable resultBg=new GradientDrawable();
        resultBg.setColor(Color.rgb(240,253,244));
        resultBg.setStroke(dp(1),Color.rgb(187,247,208));
        resultBg.setCornerRadius(dp(14));
        resultCard.setBackground(resultBg);

        TextView lineBase=text("",14,false);lineBase.setGravity(Gravity.END);
        TextView lineSubtract=text("",14,false);lineSubtract.setGravity(Gravity.END);
        TextView result=text("",24,true);result.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        resultCard.addView(lineBase,new LinearLayout.LayoutParams(-1,dp(30)));
        resultCard.addView(lineSubtract,new LinearLayout.LayoutParams(-1,dp(30)));
        resultCard.addView(result,new LinearLayout.LayoutParams(-1,dp(52)));
        box.addView(resultCard);

        final Runnable preview=()->{
            String raw=amount.getText().toString().replace(".","").replace(",","");
            double base=parseNum(raw);
            boolean useGroup=groupRadio.isChecked()&&hasGroup;
            double sub=useGroup?groupTotal(t.groupId):t.total();
            String scopeName=useGroup?("Nhóm "+groupNameFor(t.groupId)):("Bảng "+t.title);

            subtractInfo.setText((useGroup?"Tổng nhóm: ":"Tổng bảng: ")+fmt(sub));

            if(base<=0){
                lineBase.setText("Tổng tiền ban đầu: —");
                lineSubtract.setText("Trừ "+scopeName+": "+fmt(sub));
                result.setText("Chưa nhập tổng tiền ban đầu");
                result.setTextSize(16);
                result.setTextColor(muted);
                return;
            }

            double remain=base-sub;
            lineBase.setText("Tổng tiền ban đầu: "+fmt(base));
            lineSubtract.setText("− "+scopeName+": "+fmt(sub));
            result.setText("CÒN LẠI: "+fmt(remain));
            result.setTextSize(24);
            result.setTextColor(remain<0?red:Color.rgb(22,101,52));
        };

        amount.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int b,int c){preview.run();}
            public void afterTextChanged(Editable e){}
        });
        rg.setOnCheckedChangeListener((g,id)->preview.run());
        preview.run();

        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle("💵 Tính tiền mặt còn lại")
            .setView(scroll)
            .setPositiveButton("Áp dụng",null)
            .setNeutralButton("Xóa thiết lập",null)
            .setNegativeButton("Đóng",null)
            .create();

        dlg.show();

        // Gắn xử lý nút sau khi show để Android không tự đóng khi nhập sai.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String raw=amount.getText().toString().replace(".","").replace(",","");
            double base=parseNum(raw);
            if(base<=0){
                Toast.makeText(this,"Nhập hoặc tính Tổng tiền ban đầu trước",Toast.LENGTH_SHORT).show();
                return;
            }
            cashBaseAmount=base;
            cashScope=(groupRadio.isChecked()&&hasGroup)?1:0;
            saveUiState();updateCashRemainderView();haptic(v);dlg.dismiss();
        });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
            cashBaseAmount=0;cashScope=0;saveUiState();updateCashRemainderView();haptic(v);dlg.dismiss();
        });
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
                previousActiveRow=activeRow;
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

            // Nếu người dùng đã chạm trực tiếp vào một ô để sửa, giữ chế độ sửa trực tiếp
            // cho đến khi họ chọn ô/dòng khác. Nhờ vậy gõ nhiều chữ số vào Đơn giá hiện có
            // không bị tự nhảy xuống dòng sau ngay giữa lúc đang nhập.
            ensureBlankCalc(t);
        }

        t.updated=System.currentTimeMillis();
        if(fastInputMode==1 && "qty".equals(field) && !"C".equals(key) && !"⌫".equals(key) && !"cancel".equals(t.type)){
            // Vẫn cho nhập nhiều chữ số; Enter/Tab mới xác nhận chuyển dòng.
        }
        save();saveUiState();
        if(gridRecycler!=null && gridRecycler.getAdapter()!=null){
            refreshActiveRowHighlight();
            updateLiveTotals(t);
            pageIndicator.setText((tables.indexOf(t)+1)+"/"+tables.size());
            pendingScrollRow=activeRow;scrollActiveRowIntoView();
        }else{
            renderGrid();
            updateLiveTotals(t);
        }
        renderKeypads();
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
            root.put("shareHideBlank",shareHideBlank).put("densityMode",densityMode);
            JSONArray ca=new JSONArray();for(String s:collapsedGroups)ca.put(s);root.put("collapsed",ca);
            return root.toString();
        }catch(Exception e){return "{}";}
    }


    void showUndoSnackbar(String message){
        if(undoPopup!=null)undoPopup.dismiss();
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(14),0,dp(8),0);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(30,41,59));bg.setCornerRadius(dp(16));bar.setBackground(bg);
        TextView msg=text(message,13,false);msg.setTextColor(Color.WHITE);
        Button undo=smallActionButton("HOÀN TÁC");undo.setTextColor(accent);
        bar.addView(msg,new LinearLayout.LayoutParams(0,dp(48),1));bar.addView(undo,new LinearLayout.LayoutParams(dp(92),dp(42)));
        undoPopup=new PopupWindow(bar,Math.min(getResources().getDisplayMetrics().widthPixels-dp(24),dp(520)),dp(56),true);
        undoPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        undo.setOnClickListener(v->{undoPopup.dismiss();undoPopup=null;undoDelete();});
        undoPopup.showAtLocation(getWindow().getDecorView(),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,dp(compact?330:318));
        new Handler(Looper.getMainLooper()).postDelayed(()->{if(undoPopup!=null){undoPopup.dismiss();undoPopup=null;}},4000);
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
            densityMode=o.optInt("densityMode",densityMode);
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
            .putInt(DENSITY_KEY,densityMode)
            .putBoolean(SIDEBAR_MODE_KEY,sidebarCompactMode)
            .putLong(CASH_BASE_KEY,Double.doubleToLongBits(cashBaseAmount))
            .putInt(CASH_SCOPE_KEY,cashScope)
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

    int lastDataRowIndex(TableModel t){
        if(t==null)return -1;
        if("cancel".equals(t.type)){
            for(int i=t.cancelRows.size()-1;i>=0;i--)if(!t.cancelRows.get(i).blank())return i;
        }else{
            for(int i=t.calcRows.size()-1;i>=0;i--)if(!t.calcRows.get(i).blank())return i;
        }
        return -1;
    }

    void moveRow(TableModel t,int from,int to){
        if(t==null||from==to)return;
        if(t.locked){
            Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();
            return;
        }

        int last=lastDataRowIndex(t);
        if(from<0||from>last||to<0||to>last)return;

        pushUndo("Di chuyển dòng");
        if("cancel".equals(t.type)){
            CancelRow r=t.cancelRows.remove(from);
            t.cancelRows.add(to,r);
            ensureBlankCancel(t);
        }else{
            CalcRow r=t.calcRows.remove(from);
            t.calcRows.add(to,r);
            ensureBlankCalc(t);
        }

        activeRow=to;
        previousActiveRow=-1;
        pendingScrollRow=to;
        t.updated=System.currentTimeMillis();
        saveNow();
        renderAll();
        Toast.makeText(this,"Đã chuyển dòng "+(from+1)+" → "+(to+1),Toast.LENGTH_SHORT).show();
    }

    void showMoveRowDialog(TableModel t,int row){
        if(t==null)return;
        if(t.locked){
            Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();
            return;
        }

        int last=lastDataRowIndex(t);
        if(row<0||row>last)return;

        String[] actions={
            "↑ Lên 1 dòng",
            "↓ Xuống 1 dòng",
            "⇡ Lên đầu",
            "⇣ Xuống cuối",
            "↕ Chuyển đến vị trí…"
        };

        new AlertDialog.Builder(this)
            .setTitle("Di chuyển dòng "+(row+1))
            .setItems(actions,(d,i)->{
                if(i==0){
                    if(row==0)Toast.makeText(this,"Dòng đã ở trên cùng",Toast.LENGTH_SHORT).show();
                    else moveRow(t,row,row-1);
                }else if(i==1){
                    if(row>=last)Toast.makeText(this,"Dòng đã ở dưới cùng",Toast.LENGTH_SHORT).show();
                    else moveRow(t,row,row+1);
                }else if(i==2){
                    if(row==0)Toast.makeText(this,"Dòng đã ở trên cùng",Toast.LENGTH_SHORT).show();
                    else moveRow(t,row,0);
                }else if(i==3){
                    if(row>=last)Toast.makeText(this,"Dòng đã ở dưới cùng",Toast.LENGTH_SHORT).show();
                    else moveRow(t,row,last);
                }else{
                    EditText e=new EditText(this);
                    e.setHint("1 - "+(last+1));
                    e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    e.setSingleLine();
                    e.setText(String.valueOf(row+1));
                    e.setSelectAllOnFocus(true);

                    AlertDialog posDlg=new AlertDialog.Builder(this)
                        .setTitle("Chuyển dòng "+(row+1)+" đến vị trí")
                        .setView(padded(e))
                        .setPositiveButton("Chuyển",null)
                        .setNegativeButton("Hủy",null)
                        .create();
                    posDlg.setOnShowListener(x->{
                        e.requestFocus();e.selectAll();
                        posDlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                            int pos;
                            try{pos=Integer.parseInt(e.getText().toString().trim());}
                            catch(Exception ex){pos=-1;}
                            if(pos<1||pos>last+1){
                                Toast.makeText(this,"Nhập vị trí từ 1 đến "+(last+1),Toast.LENGTH_SHORT).show();
                                return;
                            }
                            haptic(v);
                            posDlg.dismiss();
                            moveRow(t,row,pos-1);
                        });
                    });
                    posDlg.show();
                }
            })
            .setNegativeButton("Đóng",null)
            .show();
    }

    void showRowDeleteDialog(TableModel t,int row){
        if(t==null)return;
        if(t.locked){
            Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();
            return;
        }

        int count="cancel".equals(t.type)?t.cancelRows.size():t.calcRows.size();
        ArrayList<Integer> realRows=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();

        for(int i=0;i<count;i++){
            boolean blank;
            String label;
            if("cancel".equals(t.type)){
                CancelRow r=t.cancelRows.get(i);
                blank=r.blank();
                String agent=(r.agent==null||r.agent.trim().isEmpty())?"Chưa có tên":r.agent;
                label="Dòng "+(i+1)+"  •  "+agent+"  •  SL "+fmt(r.qty);
            }else{
                CalcRow r=t.calcRows.get(i);
                blank=r.blank();
                label="Dòng "+(i+1)+"  •  "+fmt(r.price)+" × "+fmt(r.qty)+"  =  "+fmt(r.price*r.qty);
            }
            if(!blank){
                realRows.add(i);
                labels.add(label);
            }
        }

        if(realRows.isEmpty())return;

        boolean[] checked=new boolean[realRows.size()];
        int preselect=realRows.indexOf(row);
        if(preselect>=0)checked[preselect]=true;

        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle("Chọn dòng cần xóa")
            .setMultiChoiceItems(labels.toArray(new String[0]),checked,(d,i,on)->checked[i]=on)
            .setNeutralButton("Chọn tất cả",null)
            .setPositiveButton("Xóa đã chọn",null)
            .setNegativeButton("Hủy",null)
            .create();

        dlg.setOnShowListener(x->{
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                haptic(v);
                ListView list=dlg.getListView();
                for(int i=0;i<checked.length;i++){
                    checked[i]=true;
                    list.setItemChecked(i,true);
                }
            });

            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(red);
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                int n=0;for(boolean b:checked)if(b)n++;
                if(n==0){
                    Toast.makeText(this,"Chưa chọn dòng nào",Toast.LENGTH_SHORT).show();
                    return;
                }
                final int deleteCount=n;

                new AlertDialog.Builder(this)
                    .setTitle("Xóa "+deleteCount+" dòng?")
                    .setMessage("Các dòng phía dưới sẽ tự dồn lên.")
                    .setPositiveButton("Xóa",(d,w)->{
                        pushUndo("Xóa nhiều dòng");

                        ArrayList<Integer> indexes=new ArrayList<>();
                        for(int i=0;i<checked.length;i++)if(checked[i])indexes.add(realRows.get(i));
                        Collections.sort(indexes,Collections.reverseOrder());

                        if("cancel".equals(t.type)){
                            for(int idx:indexes)if(idx>=0&&idx<t.cancelRows.size())t.cancelRows.remove(idx);
                            ensureBlankCancel(t);
                        }else{
                            for(int idx:indexes)if(idx>=0&&idx<t.calcRows.size())t.calcRows.remove(idx);
                            ensureBlankCalc(t);
                        }

                        activeRow=Math.max(0,Math.min(activeRow,Math.max(0,t.dataRowCount()-1)));
                        previousActiveRow=-1;
                        t.updated=System.currentTimeMillis();
                        saveNow();
                        dlg.dismiss();
                        renderAll();
                        showUndoSnackbar("Đã xóa "+deleteCount+" dòng");
                    })
                    .setNegativeButton("Hủy",null)
                    .show();
            });
        });

        dlg.show();
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


    boolean handleGroupReorderDrop(DragEvent e,GroupModel target){
        if(e.getAction()!=DragEvent.ACTION_DROP)return true;
        Object st=e.getLocalState();
        if(!(st instanceof String))return true;
        String s=(String)st;
        if(!s.startsWith("GROUP:"))return true;
        reorderGroup(s.substring(6),target.id);
        return true;
    }

    void reorderGroup(String movingId,String targetId){
        if(movingId==null||targetId==null||movingId.equals(targetId))return;
        GroupModel moving=null,target=null;
        for(GroupModel g:groups){
            if(movingId.equals(g.id))moving=g;
            if(targetId.equals(g.id))target=g;
        }
        if(moving==null||target==null)return;
        pushUndo("Di chuyển nhóm");
        moving.pinned=target.pinned;
        groups.remove(moving);
        int idx=groups.indexOf(target);
        groups.add(Math.max(0,idx),moving);
        saveNow();renderSidebar();
    }

    void moveSelectedGroups(HashSet<String> ids,int delta){
        if(ids==null||ids.isEmpty()||delta==0)return;
        pushUndo("Di chuyển nhiều nhóm");

        if(delta<0){
            for(int i=1;i<groups.size();i++){
                GroupModel cur=groups.get(i),prev=groups.get(i-1);
                if(ids.contains(cur.id) && !ids.contains(prev.id) && cur.pinned==prev.pinned){
                    groups.set(i-1,cur);groups.set(i,prev);
                }
            }
        }else{
            for(int i=groups.size()-2;i>=0;i--){
                GroupModel cur=groups.get(i),next=groups.get(i+1);
                if(ids.contains(cur.id) && !ids.contains(next.id) && cur.pinned==next.pinned){
                    groups.set(i+1,cur);groups.set(i,next);
                }
            }
        }
        saveNow();
    }

    void confirmDeleteSelectedGroups(HashSet<String> ids,Dialog manager){
        if(ids==null||ids.isEmpty())return;
        int groupCount=ids.size(),tableCount=0;
        for(TableModel t:tables)if(ids.contains(t.groupId))tableCount++;
        final int gc=groupCount,tc=tableCount;

        new AlertDialog.Builder(this)
            .setTitle("Xóa "+gc+" nhóm?")
            .setMessage("Sẽ xóa luôn "+tc+" bảng nằm trong các nhóm đã chọn. Có thể Hoàn tác.")
            .setPositiveButton("Xóa nhóm và bảng",(d,w)->{
                pushUndo("Xóa nhiều nhóm");
                for(int i=tables.size()-1;i>=0;i--)if(ids.contains(tables.get(i).groupId))tables.remove(i);
                for(int i=groups.size()-1;i>=0;i--)if(ids.contains(groups.get(i).id))groups.remove(i);
                if(findTable(selectedId)==null)selectedId=tables.isEmpty()?null:tables.get(0).id;
                ids.clear();
                saveNow();manager.dismiss();renderAll();
                showUndoSnackbar("Đã xóa "+gc+" nhóm và "+tc+" bảng");
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

    void confirmDeleteEverything(Dialog manager){
        if(tables.isEmpty()&&groups.isEmpty()){
            Toast.makeText(this,"Không có dữ liệu để xóa",Toast.LENGTH_SHORT).show();
            return;
        }
        final int tc=tables.size(),gc=groups.size();
        new AlertDialog.Builder(this)
            .setTitle("Xóa TẤT CẢ bảng và nhóm?")
            .setMessage("Sẽ xóa "+tc+" bảng và "+gc+" nhóm, gồm cả các bảng Chưa nhóm. Có thể Hoàn tác ngay sau khi xóa.")
            .setPositiveButton("XÓA TẤT CẢ",(d,w)->{
                pushUndo("Xóa tất cả bảng và nhóm");
                tables.clear();groups.clear();collapsedGroups.clear();sidebarSelectedIds.clear();
                selectedId=null;activeRow=0;previousActiveRow=-1;
                saveNow();
                if(manager!=null)manager.dismiss();
                renderAll();
                showUndoSnackbar("Đã xóa tất cả "+tc+" bảng và "+gc+" nhóm");
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

    String managerTime(long ms){
        if(ms<=0)return "không rõ";
        return new SimpleDateFormat("dd/MM/yy HH:mm",Locale.getDefault()).format(new Date(ms));
    }

    long groupCreatedTime(GroupModel g){
        if(g==null)return 0;
        if(g.created>0)return g.created;
        long min=Long.MAX_VALUE;
        for(TableModel t:tables)if(g.id.equals(t.groupId)){
            long v=t.created>0?t.created:t.updated;
            if(v>0&&v<min)min=v;
        }
        return min==Long.MAX_VALUE?0:min;
    }

    long groupLatestEditedTime(GroupModel g){
        if(g==null)return 0;
        long max=0;
        for(TableModel t:tables)if(g.id.equals(t.groupId) && t.updated>max)max=t.updated;
        // Nhóm rỗng: dùng ngày tạo để không hiện một giá trị vô nghĩa.
        return max>0?max:groupCreatedTime(g);
    }

    String tableManagerDates(TableModel t){
        return "Tạo "+managerTime(t.created)+"  •  Sửa "+managerTime(t.updated);
    }

    String groupManagerDates(GroupModel g){
        return "Tạo "+managerTime(groupCreatedTime(g))+"  •  Sửa mới nhất "+managerTime(groupLatestEditedTime(g));
    }

    void moveGroupBy(GroupModel g,int delta){
        if(g==null||delta==0)return;
        int idx=groups.indexOf(g);if(idx<0)return;
        int target=idx+delta;
        if(target<0||target>=groups.size())return;
        pushUndo("Di chuyển nhóm");
        groups.remove(idx);
        groups.add(target,g);
        saveNow();
    }

    void showTableManagerSheet(){showTableManagerSheet(null);}

    void showTableManagerSheet(String preselectId){
        final Dialog dlg=new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int managerScreenW=getResources().getConfiguration().screenWidthDp;

        topInsetSpacer=new View(this);
        topInsetSpacer.setBackgroundColor(Color.WHITE);
        root.addView(topInsetSpacer,new LinearLayout.LayoutParams(-1,0));
        applyTopSystemInset();
        root.setPadding(dp(managerScreenW>=600?14:10),dp(8),dp(managerScreenW>=600?14:10),dp(10));
        root.setBackgroundColor(Color.rgb(248,250,252));

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Quản lý bảng & nhóm",managerScreenW<380?18:20,true);
        TextView hint=text(managerScreenW<420?"Vuốt: thao tác/chọn • Giữ ≡: kéo":"Vuốt trái: thao tác • Vuốt phải: chọn • Giữ ≡: kéo",11,false);
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
        tools.addView(backup,new LinearLayout.LayoutParams(0,dp(managerScreenW>=600?46:44),1));
        tools.addView(restore,new LinearLayout.LayoutParams(0,dp(managerScreenW>=600?46:44),1));
        tools.addView(sort,new LinearLayout.LayoutParams(0,dp(managerScreenW>=600?46:44),1));
        tools.addView(settings,new LinearLayout.LayoutParams(0,dp(managerScreenW>=600?46:44),1));
        root.addView(tools);

        final HashSet<String> selectedIds=new HashSet<>();
        final HashSet<String> selectedGroupIds=new HashSet<>();
        if(preselectId!=null&&!preselectId.isEmpty())selectedIds.add(preselectId);

        LinearLayout selectionBar=new LinearLayout(this);selectionBar.setGravity(Gravity.CENTER_VERTICAL);selectionBar.setPadding(dp(4),dp(4),dp(4),dp(4));
        GradientDrawable selBg=new GradientDrawable();selBg.setColor(Color.rgb(239,246,255));selBg.setCornerRadius(dp(12));selectionBar.setBackground(selBg);
        TextView selectionCount=text("",13,true);selectionCount.setTextColor(accent);
        Button selGroup=smallActionButton("+ Nhóm");
        Button selMove=smallActionButton("Chuyển");
        Button selCopy=smallActionButton("Copy");
        Button selDelete=smallActionButton("Xóa");
        Button selGroupUp=smallActionButton("↑ Lên");
        Button selGroupDown=smallActionButton("↓ Xuống");
        Button selGroupDelete=smallActionButton("Xóa nhóm");
        selGroupDelete.setTextColor(red);

        selectionBar.addView(selectionCount,new LinearLayout.LayoutParams(0,dp(44),1));
        selectionBar.addView(selGroup,new LinearLayout.LayoutParams(dp(82),dp(40)));
        selectionBar.addView(selMove,new LinearLayout.LayoutParams(dp(82),dp(40)));
        selectionBar.addView(selCopy,new LinearLayout.LayoutParams(dp(68),dp(40)));
        selectionBar.addView(selDelete,new LinearLayout.LayoutParams(dp(64),dp(40)));
        selectionBar.addView(selGroupUp,new LinearLayout.LayoutParams(dp(66),dp(40)));
        selectionBar.addView(selGroupDown,new LinearLayout.LayoutParams(dp(76),dp(40)));
        selectionBar.addView(selGroupDelete,new LinearLayout.LayoutParams(dp(88),dp(40)));
        root.addView(selectionBar,new LinearLayout.LayoutParams(-1,dp(50)));

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

        Button deleteEverything=smallActionButton("⚠ Xóa tất cả bảng & nhóm");
        deleteEverything.setTextColor(red);
        root.addView(deleteEverything,new LinearLayout.LayoutParams(-1,dp(46)));

        final Runnable[] rebuild=new Runnable[1];
        rebuild[0]=()->{
            list.removeAllViews();
            boolean groupMode=!selectedGroupIds.isEmpty();
            boolean tableMode=!selectedIds.isEmpty();

            selectionCount.setText(groupMode
                    ? selectedGroupIds.size()+" nhóm đã chọn"
                    : selectedIds.size()+" bảng đã chọn");
            selectionBar.setVisibility((groupMode||tableMode)?View.VISIBLE:View.GONE);
            bottom.setVisibility((groupMode||tableMode)?View.GONE:View.VISIBLE);
            deleteEverything.setVisibility((groupMode||tableMode)?View.GONE:View.VISIBLE);

            selGroup.setVisibility(groupMode?View.GONE:View.VISIBLE);
            selMove.setVisibility(groupMode?View.GONE:View.VISIBLE);
            selCopy.setVisibility(groupMode?View.GONE:View.VISIBLE);
            selDelete.setVisibility(groupMode?View.GONE:View.VISIBLE);

            selGroupUp.setVisibility(groupMode?View.VISIBLE:View.GONE);
            selGroupDown.setVisibility(groupMode?View.VISIBLE:View.GONE);
            selGroupDelete.setVisibility(groupMode?View.VISIBLE:View.GONE);

            // Chưa nhóm
            TextView ungroupedHeader=managerSectionHeader("Chưa nhóm",fmt(groupTotal(UNGROUPED)),false);
            ungroupedHeader.setOnDragListener((v,e)->managerGroupDrop(e,UNGROUPED,dlg,rebuild[0]));
            list.addView(ungroupedHeader);
            for(TableModel t:managerTablesForGroup(UNGROUPED,searchQuery[0])){
                list.addView(managerTableRow(t,UNGROUPED,selectedIds,dlg,rebuild[0]));
            }

            for(GroupModel g:orderedGroups()){
                if(!managerGroupVisible(g,searchQuery[0]))continue;
                LinearLayout gh=new LinearLayout(this);gh.setGravity(Gravity.CENTER_VERTICAL);
                gh.setPadding(dp(8),dp(5),dp(4),dp(5));gh.setBackgroundColor(groupBg);
                CheckBox gcheck=new CheckBox(this);
                gcheck.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
                gcheck.setChecked(selectedGroupIds.contains(g.id));
                LinearLayout ginfo=new LinearLayout(this);ginfo.setOrientation(LinearLayout.VERTICAL);ginfo.setGravity(Gravity.CENTER_VERTICAL);
                TextView gname=text((g.pinned?"📌 ":"")+(collapsedGroups.contains(g.id)?"▸ ":"▾ ")+g.name,16,true);
                TextView gdates=text(groupManagerDates(g),10,false);gdates.setTextColor(Color.rgb(100,116,139));
                gdates.setSingleLine(true);gdates.setEllipsize(android.text.TextUtils.TruncateAt.END);
                ginfo.addView(gname);ginfo.addView(gdates);

                TextView sum=text(inGroup(g.id).size()+" bảng\n"+fmt(groupTotal(g.id)),12,true);
                sum.setTextColor(accent);sum.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
                TextView gdrag=text("≡",22,true);gdrag.setGravity(Gravity.CENTER);gdrag.setTextColor(muted);
                TextView more=text("⋮",24,true);more.setGravity(Gravity.CENTER);
                gh.addView(gcheck,new LinearLayout.LayoutParams(dp(46),dp(66)));
                gh.addView(ginfo,new LinearLayout.LayoutParams(0,dp(66),1));
                gh.addView(sum,new LinearLayout.LayoutParams(dp(130),dp(66)));
                gh.addView(gdrag,new LinearLayout.LayoutParams(dp(44),dp(66)));
                gh.addView(more,new LinearLayout.LayoutParams(dp(44),dp(66)));

                gcheck.setOnCheckedChangeListener((b,on)->{
                    if(on){
                        selectedIds.clear();
                        selectedGroupIds.add(g.id);
                    }else selectedGroupIds.remove(g.id);
                    rebuild[0].run();
                });
                gdrag.setOnLongClickListener(v->{
                    haptic(v);
                    ClipData cd=ClipData.newPlainText("group",g.id);
                    v.startDragAndDrop(cd,new View.DragShadowBuilder(gh),"GROUP:"+g.id,0);
                    return true;
                });
                gh.setOnDragListener((v,e)->{
                    if(e.getAction()==DragEvent.ACTION_DROP){
                        Object st=e.getLocalState();
                        if(st instanceof String && ((String)st).startsWith("GROUP:")){
                            String movingId=((String)st).substring(6);
                            reorderGroup(movingId,g.id);
                            rebuild[0].run();renderSidebar();return true;
                        }
                        return managerGroupDrop(e,g.id,dlg,rebuild[0]);
                    }
                    return true;
                });
                gh.setOnClickListener(v->{
                    if(!selectedGroupIds.isEmpty()){
                        if(selectedGroupIds.contains(g.id))selectedGroupIds.remove(g.id);
                        else{selectedIds.clear();selectedGroupIds.add(g.id);}
                        rebuild[0].run();
                        return;
                    }
                    if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);
                    rebuild[0].run();
                });
                gh.setOnLongClickListener(v->{
                    haptic(v);
                    selectedIds.clear();
                    selectedGroupIds.add(g.id);
                    rebuild[0].run();
                    return true;
                });
                more.setOnClickListener(v->showManagerGroupActions(g,dlg,rebuild[0]));
                /* group drag listener handled above */
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

        View.OnClickListener createSelectedGroup=v->{
            haptic(v);
            if(selectedIds.isEmpty()){
                dlg.dismiss();createGroupDialog();return;
            }
            EditText e=new EditText(this);e.setHint("Tên nhóm");e.setSingleLine();
            new AlertDialog.Builder(this)
                .setTitle("Tạo nhóm từ "+selectedIds.size()+" bảng")
                .setView(padded(e))
                .setPositiveButton("Tạo",(d,w)->{
                    String name=e.getText().toString().trim();
                    if(name.isEmpty())return;
                    pushUndo("Tạo nhóm từ nhiều bảng");
                    GroupModel g=new GroupModel();g.id=id();g.name=name;groups.add(g);
                    for(TableModel tb:tables)if(selectedIds.contains(tb.id))tb.groupId=g.id;
                    selectedIds.clear();saveNow();rebuild[0].run();renderAll();
                })
                .setNegativeButton("Hủy",null).show();
        };
        newGroup.setOnClickListener(createSelectedGroup);
        selGroup.setOnClickListener(createSelectedGroup);

        selMove.setOnClickListener(v->{
            haptic(v);
            if(selectedIds.isEmpty()){
                Toast.makeText(this,"Chưa chọn bảng",Toast.LENGTH_SHORT).show();
                return;
            }
            showMoveSelectedDialog(selectedIds,dlg,()->{
                selectedIds.clear();
                rebuild[0].run();
            });
        });

        selCopy.setOnClickListener(v->{
            haptic(v);
            if(selectedIds.isEmpty()){
                Toast.makeText(this,"Chưa chọn bảng",Toast.LENGTH_SHORT).show();
                return;
            }
            HashSet<String> before=new HashSet<>();
            for(TableModel tb:tables)before.add(tb.id);

            copySelectedTables(new HashSet<>(selectedIds));

            selectedIds.clear();
            for(TableModel tb:tables)if(!before.contains(tb.id))selectedIds.add(tb.id);

            rebuild[0].run();
            renderSidebar();
        });

        selDelete.setOnClickListener(v->{
            haptic(v);
            if(selectedIds.isEmpty()){
                Toast.makeText(this,"Chưa chọn bảng",Toast.LENGTH_SHORT).show();
                return;
            }
            confirmDeleteSelected(selectedIds,dlg);
        });

        selGroupUp.setOnClickListener(v->{
            haptic(v);
            if(selectedGroupIds.isEmpty())return;
            moveSelectedGroups(selectedGroupIds,-1);
            rebuild[0].run();renderSidebar();
        });

        selGroupDown.setOnClickListener(v->{
            haptic(v);
            if(selectedGroupIds.isEmpty())return;
            moveSelectedGroups(selectedGroupIds,1);
            rebuild[0].run();renderSidebar();
        });

        selGroupDelete.setOnClickListener(v->{
            haptic(v);
            if(selectedGroupIds.isEmpty())return;
            confirmDeleteSelectedGroups(selectedGroupIds,dlg);
        });

        deleteEverything.setOnClickListener(v->{
            haptic(v);
            confirmDeleteEverything(dlg);
        });

        selectAll.setOnClickListener(v->{
            selectedGroupIds.clear();
            if(selectedIds.size()==tables.size())selectedIds.clear();
            else {selectedIds.clear();for(TableModel t:tables)selectedIds.add(t.id);}
            haptic(v);rebuild[0].run();
        });
        move.setOnClickListener(v->{
            haptic(v);
            if(selectedIds.isEmpty()){Toast.makeText(this,"Vuốt phải hoặc chạm ô chọn để chọn bảng",Toast.LENGTH_SHORT).show();return;}
            showMoveSelectedDialog(selectedIds,dlg,rebuild[0]);
        });
        delete.setOnClickListener(v->{
            haptic(v);
            if(selectedIds.isEmpty()){Toast.makeText(this,"Chưa chọn bảng",Toast.LENGTH_SHORT).show();return;}
            confirmDeleteSelected(selectedIds,dlg);
        });

        dlg.setContentView(root);
        final int managerWdp=getResources().getConfiguration().screenWidthDp;
        final boolean managerPhone=managerWdp<600;
        final int managerWidth=managerPhone
            ?WindowManager.LayoutParams.MATCH_PARENT
            :Math.min(getResources().getDisplayMetrics().widthPixels-dp(40),dp(managerWdp>=1100?820:720));
        final int managerHeight=(int)(getResources().getDisplayMetrics().heightPixels*(managerPhone?0.90f:0.84f));

        Window win=dlg.getWindow();
        if(win!=null){
            win.setBackgroundDrawableResource(android.R.color.transparent);
            win.setGravity(managerPhone?Gravity.BOTTOM:Gravity.CENTER);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams();
            lp.copyFrom(win.getAttributes());
            lp.width=managerWidth;lp.height=managerHeight;
            win.setAttributes(lp);
        }
        dlg.show();
        win=dlg.getWindow();
        if(win!=null){
            win.setGravity(managerPhone?Gravity.BOTTOM:Gravity.CENTER);
            win.setLayout(managerWidth,managerHeight);
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
        info.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text(t.title,16,true);
        TextView meta=text((t.locked?"🔒 • ":"")+t.dataRowCount()+" dòng • "+fmt(t.total()),12,false);meta.setTextColor(muted);
        TextView dates=text(tableManagerDates(t),10,false);dates.setTextColor(Color.rgb(100,116,139));
        dates.setSingleLine(true);dates.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name);info.addView(meta);info.addView(dates);

        TextView drag=text("≡",24,true);drag.setGravity(Gravity.CENTER);drag.setTextColor(navy);
        TextView more=text("⋮",24,true);more.setGravity(Gravity.CENTER);

        row.addView(cb,new LinearLayout.LayoutParams(dp(48),dp(76)));
        row.addView(info,new LinearLayout.LayoutParams(0,dp(76),1));
        row.addView(drag,new LinearLayout.LayoutParams(dp(48),dp(76)));
        row.addView(more,new LinearLayout.LayoutParams(dp(44),dp(76)));

        cb.setOnCheckedChangeListener((b,on)->{if(on)selectedIds.add(t.id);else selectedIds.remove(t.id);if(rebuild!=null)rebuild.run();});
        info.setOnClickListener(v->{selectedId=t.id;activeRow=0;activeField="cancel".equals(t.type)?"qty":"price";explicitCellSelection=false;dlg.dismiss();renderAll();});
        info.setOnLongClickListener(v->{selectedIds.add(t.id);cb.setChecked(true);haptic(v);return true;});

        drag.setOnLongClickListener(v->{
            haptic(v);
            if(selectedIds.contains(t.id) && selectedIds.size()>1){
                managerDragSelection=new HashSet<>(selectedIds);
                ClipData cd=ClipData.newPlainText("tables",String.valueOf(selectedIds.size()));
                v.startDragAndDrop(cd,new View.DragShadowBuilder(row),"MGRMULTI:"+t.id,0);
            }else{
                ClipData cd=ClipData.newPlainText("table",t.id);
                v.startDragAndDrop(cd,new View.DragShadowBuilder(row),t.id,0);
            }
            return true;
        });

        row.setOnDragListener((v,e)->{
            if(e.getAction()==DragEvent.ACTION_DROP){
                Object st=e.getLocalState();if(!(st instanceof String))return true;
                String state=(String)st;
                if(state.startsWith("MGRMULTI:") && managerDragSelection!=null&&!managerDragSelection.isEmpty()){
                    pushUndo("Kéo nhiều bảng");
                    ArrayList<TableModel> movingList=new ArrayList<>();
                    for(TableModel tb:new ArrayList<>(tables))if(managerDragSelection.contains(tb.id))movingList.add(tb);
                    tables.removeAll(movingList);
                    int idx=Math.max(0,tables.indexOf(t));
                    for(TableModel tb:movingList){tb.groupId=gid;tables.add(Math.min(idx++,tables.size()),tb);}
                    saveNow();rebuild.run();renderAll();return true;
                }
                TableModel moving=findTable(state);if(moving==null||moving==t)return true;
                tables.remove(moving);moving.groupId=gid;int idx=tables.indexOf(t);tables.add(Math.max(0,idx),moving);
                saveNow();rebuild.run();renderAll();return true;
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
        if(e.getAction()!=DragEvent.ACTION_DROP)return true;
        Object st=e.getLocalState();if(!(st instanceof String))return true;
        String state=(String)st;

        if(state.startsWith("MGRMULTI:")){
            // Use the checked state from visible manager checkboxes by reading selected rows is not available here.
            // Delegate via temporary manager selection snapshot stored in tag is avoided; manager uses helper below.
            HashSet<String> ids=managerDragSelection;
            if(ids!=null&&!ids.isEmpty()){
                pushUndo("Kéo nhiều bảng sang nhóm");
                for(TableModel tb:tables)if(ids.contains(tb.id))tb.groupId=gid;
                saveNow();rebuild.run();renderAll();
            }
            return true;
        }

        TableModel tb=findTable(state);
        if(tb!=null){
            pushUndo("Kéo bảng sang nhóm");
            tables.remove(tb);tb.groupId=gid;tables.add(tb);
            saveNow();rebuild.run();renderAll();
        }
        return true;
    }

    void installManagerTableSwipe(View row,TableModel t,HashSet<String> selectedIds,CheckBox cb,Dialog dlg,Runnable rebuild){
        final float[] down={0,0};
        row.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=e.getX();down[1]=e.getY();return false;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                float width=Math.max(dp(240),row.getWidth());

                if(dx<0 && Math.abs(dx)>width*.58f && Math.abs(dx)>Math.abs(dy)*.85f){
                    haptic(v);
                    if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return true;}
                    pushUndo("Xóa nhanh bảng");
                    tables.remove(t);selectedIds.remove(t.id);
                    if(t.id.equals(selectedId))selectedId=tables.isEmpty()?null:tables.get(0).id;
                    saveNow();rebuild.run();renderAll();showUndoSnackbar("Đã xóa "+t.title);
                    return true;
                }

                if(dx<-dp(26)&&Math.abs(dx)>Math.abs(dy)*.85f){
                    haptic(v);showManagerTableActions(t,dlg,rebuild);return true;
                }
                if(dx>dp(26)&&Math.abs(dx)>Math.abs(dy)*.85f){
                    haptic(v);
                    if(selectedIds.contains(t.id)){selectedIds.remove(t.id);cb.setChecked(false);}
                    else{selectedIds.add(t.id);cb.setChecked(true);}
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
                if(dx<-dp(26)&&Math.abs(dx)>Math.abs(dy)*.85f){
                    haptic(v);showManagerGroupActions(g,dlg,rebuild);return true;
                }
            }
            return false;
        });
    }

    void showManagerTableActions(TableModel t,Dialog manager,Runnable rebuild){
        String[] actions={"Mở bảng","Copy bảng",t.locked?"Mở khóa bảng":"Khóa bảng","Đổi tên","Chuyển nhóm","Xóa bảng"};
        new AlertDialog.Builder(this).setTitle(t.title).setItems(actions,(d,i)->{
            if(i==0){selectedId=t.id;manager.dismiss();renderAll();}
            else if(i==1){
                showCopyOptions(t,()->{
                    rebuild.run();
                    renderSidebar();
                });
            }
            else if(i==2){toggleLock(t);rebuild.run();}
            else if(i==3){selectedId=t.id;manager.dismiss();renameCurrent();}
            else if(i==4)showMoveOneDialog(t,manager,rebuild);
            else if(i==5){if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle("Xóa "+t.title+"?")
                    .setPositiveButton("Xóa",(x,w)->{pushUndo("Xóa bảng");tables.remove(t);if(t.id.equals(selectedId))selectedId=tables.isEmpty()?null:tables.get(0).id;save();rebuild.run();renderAll();})
                    .setNegativeButton("Hủy",null).show();}
        }).show();
    }

    void showManagerGroupActions(GroupModel g,Dialog manager,Runnable rebuild){
        String[] actions={"Thu gọn / Mở rộng",g.pinned?"Bỏ ghim nhóm":"Ghim nhóm","Di chuyển nhóm lên","Di chuyển nhóm xuống","Đổi tên nhóm","Copy cả nhóm","Chia sẻ nhóm","Xóa toàn bộ số lượng trong nhóm","Xóa nhóm và toàn bộ bảng"};
        new AlertDialog.Builder(this).setTitle(g.name).setItems(actions,(d,i)->{
            if(i==0){if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);saveUiState();rebuild.run();}
            else if(i==1){togglePinGroup(g);rebuild.run();}
            else if(i==2){moveGroupBy(g,-1);rebuild.run();renderSidebar();}
            else if(i==3){moveGroupBy(g,1);rebuild.run();renderSidebar();}
            else if(i==4){manager.dismiss();renameGroup(g);}
            else if(i==5){copyGroup(g);rebuild.run();}
            else if(i==6){shareGroup(g);}
            else if(i==7)confirmClearGroupQuantities(g,()->{rebuild.run();renderAll();});
            else{
                manager.dismiss();
                deleteGroup(g);
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


    void copySelectedTables(HashSet<String> ids){
        if(ids==null||ids.isEmpty())return;
        ArrayList<TableModel> srcs=new ArrayList<>();
        for(TableModel t:tables)if(ids.contains(t.id))srcs.add(t);
        for(TableModel s:srcs){
            TableModel t=new TableModel();t.id=id();t.type=s.type;t.title=s.title+" - Bản sao";t.groupId=s.groupId;t.updated=System.currentTimeMillis();
            if("cancel".equals(s.type)){for(CancelRow r:s.cancelRows)if(!r.blank())t.cancelRows.add(new CancelRow(r.agent,r.qty));ensureBlankCancel(t);}
            else{for(CalcRow r:s.calcRows)if(!r.blank()){CalcRow c=new CalcRow();c.price=r.price;c.qty=r.qty;t.calcRows.add(c);}ensureBlankCalc(t);}
            tables.add(t);
        }
        saveNow();renderAll();Toast.makeText(this,"Đã copy "+srcs.size()+" bảng",Toast.LENGTH_SHORT).show();
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
                selectedIds.clear();
                saveNow();manager.dismiss();renderAll();
                showUndoSnackbar("Đã xóa "+n+" bảng");
            })
            .setNegativeButton("Hủy",null).show();
    }

    Button smallActionButton(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setMinHeight(0);b.setMinWidth(0);
        GradientDrawable d=new GradientDrawable();d.setColor(Color.rgb(222,235,249));d.setCornerRadius(dp(10));b.setBackground(d);
        return b;
    }


    void closeRevealedSwipe(){
        if(revealedSwipeRow!=null){
            View content=revealedSwipeRow.findViewWithTag("swipe_content");
            if(content!=null)content.animate().translationX(0f).setDuration(130).start();
            revealedSwipeRow=null;
        }
    }


    Button sidebarIconActionButton(String icon,boolean danger,String desc){
        Button b=new Button(this);
        b.setText(icon);b.setAllCaps(false);b.setTextSize(18);
        b.setContentDescription(desc);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(0,0,0,0);
        b.setTextColor(danger?Color.WHITE:ink);
        GradientDrawable d=new GradientDrawable();
        d.setColor(danger?red:Color.rgb(241,245,249));
        d.setCornerRadius(dp(10));
        b.setBackground(d);b.setStateListAnimator(null);
        return b;
    }

    Button swipeActionButton(String label,boolean danger){
        Button b=new Button(this);
        b.setText(label);b.setAllCaps(false);b.setTextSize(12);
        b.setTextColor(danger?Color.WHITE:ink);
        GradientDrawable d=new GradientDrawable();
        d.setColor(danger?red:Color.rgb(241,245,249));
        d.setCornerRadius(dp(10));
        b.setBackground(d);b.setStateListAnimator(null);
        return b;
    }

    FrameLayout makeSwipeFrame(View content,LinearLayout actions,int revealDp){
        FrameLayout frame=new FrameLayout(this);
        frame.setClipChildren(true);frame.setClipToPadding(true);
        FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(dp(revealDp),ViewGroup.LayoutParams.MATCH_PARENT,Gravity.END);
        frame.addView(actions,ap);
        content.setTag("swipe_content");
        frame.addView(content,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    void installRevealSwipe(FrameLayout frame,View content,int revealDp,Runnable fullSwipeAction){
        final float[] down={0,0};
        final boolean[] crossed={false,false};
        frame.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                down[0]=e.getX();down[1]=e.getY();
                crossed[0]=false;crossed[1]=false;
                if(revealedSwipeRow!=null&&revealedSwipeRow!=frame)closeRevealedSwipe();
                return false;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                if(Math.abs(dx)>dp(5)&&Math.abs(dx)>Math.abs(dy)*0.82f){
                    float width=Math.max(dp(220),frame.getWidth());
                    float max=Math.max(dp(revealDp),width*0.92f);
                    float x=Math.max(-max,Math.min(0,dx));
                    content.setTranslationX(x);

                    if(dx<-dp(24)&&!crossed[0]){
                        crossed[0]=true;
                        content.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    if(Math.abs(dx)>width*0.55f&&!crossed[1]){
                        crossed[1]=true;
                        content.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                    return true;
                }
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                float dx=e.getX()-down[0],dy=e.getY()-down[1];
                float width=Math.max(dp(220),frame.getWidth());

                // Vuốt dài khoảng 55% = xóa nhanh.
                if(dx<0 && Math.abs(dx)>width*0.55f && Math.abs(dx)>Math.abs(dy)*0.8f){
                    content.animate().translationX(-width).alpha(.72f).setDuration(130)
                        .withEndAction(()->{
                            revealedSwipeRow=null;
                            if(fullSwipeAction!=null)fullSwipeAction.run();
                        }).start();
                    return true;
                }

                // Chỉ cần vuốt trái nhẹ là mở action.
                if(dx<-dp(18) && Math.abs(dx)>Math.abs(dy)*0.8f){
                    content.animate().translationX(-dp(revealDp)).alpha(1f).setDuration(120)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
                    revealedSwipeRow=frame;
                    return true;
                }

                // Vuốt phải nhẹ là đóng.
                if(dx>dp(18)){
                    content.animate().translationX(0f).alpha(1f).setDuration(105).start();
                    if(revealedSwipeRow==frame)revealedSwipeRow=null;
                    return true;
                }

                if(content.getTranslationX()!=0f){
                    boolean open=content.getTranslationX()<-dp(18);
                    content.animate().translationX(open?-dp(revealDp):0f).alpha(1f).setDuration(105).start();
                    if(open)revealedSwipeRow=frame;else if(revealedSwipeRow==frame)revealedSwipeRow=null;
                    return true;
                }
            }
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                content.animate().translationX(0f).alpha(1f).setDuration(90).start();
            }
            return false;
        });
    }

    void quickDeleteTable(TableModel t){
        if(t==null)return;
        if(t.locked){Toast.makeText(this,"Bảng đang khóa",Toast.LENGTH_SHORT).show();renderAll();return;}
        pushUndo("Xóa nhanh bảng");
        int idx=tables.indexOf(t);
        tables.remove(t);
        if(t.id.equals(selectedId))selectedId=tables.isEmpty()?null:tables.get(Math.max(0,Math.min(idx,tables.size()-1))).id;
        save();renderAll();
        showUndoSnackbar("Đã xóa "+t.title);
    }

    void quickDeleteGroup(GroupModel g){
        if(g==null)return;
        pushUndo("Xóa nhanh nhóm");
        for(TableModel t:tables)if(g.id.equals(t.groupId))t.groupId=UNGROUPED;
        groups.remove(g);
        save();renderAll();
        showUndoSnackbar("Đã xóa nhóm "+g.name);
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
        String[] a={"Đổi tên","Chuyển nhóm","Copy bảng",t.locked?"Mở khóa":"Khóa bảng","Xóa"};
        new AlertDialog.Builder(this).setTitle(t.title).setItems(a,(d,i)->{
            selectedId=t.id;
            if(i==0)renameCurrent();
            else if(i==1)moveCurrentGroup();
            else if(i==2)showCopyOptions(t);
            else if(i==3)toggleLock(t);
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

    void showGroupStats(GroupModel g){
        if(g==null)return;
        int count=0,cancel=0,locked=0;double total=0;
        for(TableModel t:tables)if(g.id.equals(t.groupId)){count++;if("cancel".equals(t.type))cancel++;if(t.locked)locked++;total+=t.total();}
        String msg=count+" bảng\n"+cancel+" bảng hủy vé\n"+locked+" bảng đang khóa\nTổng nhóm: "+fmt(total);
        new AlertDialog.Builder(this).setTitle(g.name).setMessage(msg)
            .setPositiveButton("Đóng",null).setNeutralButton("Quản lý",(d,w)->showTableManagerSheet()).show();
    }

    void showGroupMenu(View anchor,GroupModel g){
        PopupMenu p=new PopupMenu(this,anchor);
        p.getMenu().add(collapsedGroups.contains(g.id)?"Mở rộng nhóm":"Thu gọn nhóm");
        p.getMenu().add(g.pinned?"Bỏ ghim nhóm":"Ghim nhóm");
        p.getMenu().add("Di chuyển nhóm lên");
        p.getMenu().add("Di chuyển nhóm xuống");
        p.getMenu().add("Đổi tên nhóm");
        p.getMenu().add("Copy cả nhóm");
        p.getMenu().add("Chia sẻ nhóm");
        p.getMenu().add("Xóa toàn bộ số lượng trong nhóm");
        p.getMenu().add("Xóa nhóm và toàn bộ bảng");
        p.setOnMenuItemClickListener(i->{
            String s=i.getTitle().toString();
            if(s.startsWith("Mở")||s.startsWith("Thu")){
                if(collapsedGroups.contains(g.id))collapsedGroups.remove(g.id);else collapsedGroups.add(g.id);
                renderSidebar();
            }else if(s.contains("ghim")){
                togglePinGroup(g);
            }else if(s.startsWith("Di chuyển nhóm lên")){
                moveGroupBy(g,-1);renderAll();
            }else if(s.startsWith("Di chuyển nhóm xuống")){
                moveGroupBy(g,1);renderAll();
            }else if(s.startsWith("Đổi")){
                renameGroup(g);
            }else if(s.startsWith("Copy")){
                copyGroup(g);
            }else if(s.startsWith("Chia sẻ")){
                shareGroup(g);
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
        showCopyOptions(src,null);
    }

    void showCopyOptions(TableModel src,Runnable afterCopy){
        if(src==null)return;
        String[] opts="cancel".equals(src.type)
            ?new String[]{"Copy toàn bộ","Copy tên đại lý, xóa số lượng","Copy bảng trống"}
            :new String[]{"Copy toàn bộ","Copy đơn giá, xóa số lượng","Copy bảng trống"};
        new AlertDialog.Builder(this)
            .setTitle("Copy "+src.title)
            .setItems(opts,(d,i)->{
                copyTableMode(src,i);
                if(afterCopy!=null)afterCopy.run();
            }).show();
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
        saveNow();renderAll();Toast.makeText(this,"Đã copy bảng",Toast.LENGTH_SHORT).show();
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
                save();renderAll();showUndoSnackbar("Đã xóa toàn bộ số lượng");
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
    void moveCurrentGroup(){
        TableModel t=selected();if(t==null)return;
        ArrayList<String> names=new ArrayList<>();names.add("Chưa nhóm");
        for(GroupModel g:groups)names.add(g.name);
        int checked=0;
        if(t.groupId!=null&&!t.groupId.isEmpty()){
            for(int i=0;i<groups.size();i++)if(t.groupId.equals(groups.get(i).id)){checked=i+1;break;}
        }
        new AlertDialog.Builder(this)
            .setTitle("Chuyển "+t.title+" vào nhóm")
            .setSingleChoiceItems(names.toArray(new String[0]),checked,(d,i)->{
                String gid=i==0?UNGROUPED:groups.get(i-1).id;
                if(!gid.equals(t.groupId)){pushUndo("Chuyển nhóm");t.groupId=gid;save();renderAll();}
                d.dismiss();
            }).show();
    }
    void renameGroup(GroupModel g){EditText e=new EditText(this);e.setText(g.name);new AlertDialog.Builder(this).setTitle("Đổi tên nhóm").setView(padded(e)).setPositiveButton("Lưu",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty()){g.name=s;save();renderAll();}}).setNegativeButton("Hủy",null).show();}
    void deleteGroup(GroupModel g){
        if(g==null)return;
        int count=0;for(TableModel t:tables)if(g.id.equals(t.groupId))count++;
        final int groupCount=count;
        new AlertDialog.Builder(this)
            .setTitle("Xóa nhóm "+g.name+"?")
            .setMessage("Sẽ xóa luôn "+groupCount+" bảng trong nhóm. Có thể Hoàn tác sau khi xóa.")
            .setPositiveButton("Xóa nhóm và bảng",(d,w)->{
                pushUndo("Xóa nhóm và bảng");
                for(int i=tables.size()-1;i>=0;i--)if(g.id.equals(tables.get(i).groupId))tables.remove(i);
                groups.remove(g);
                if(findTable(selectedId)==null)selectedId=tables.isEmpty()?null:tables.get(0).id;
                saveNow();renderAll();
                showUndoSnackbar("Đã xóa nhóm "+g.name+" và "+groupCount+" bảng");
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

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
        TextView dtitle=text("Mật độ hiển thị",14,true);dtitle.setPadding(0,dp(10),0,0);box.addView(dtitle);
        RadioGroup density=new RadioGroup(this);
        String[] dlabels={"Tự động","Compact - nhiều dòng hơn","Comfortable - dễ chạm hơn"};
        for(int i=0;i<dlabels.length;i++){RadioButton r=new RadioButton(this);r.setText(dlabels[i]);r.setId(200+i);density.addView(r);}
        density.check(200+densityMode);box.addView(density);
        new AlertDialog.Builder(this).setTitle("Cài đặt").setView(box)
            .setPositiveButton("Lưu",(d,w)->{
                int id=rg.getCheckedRadioButtonId();fastInputMode=Math.max(0,id-100);
                shareHideBlank=cb.isChecked();
                densityMode=Math.max(0,density.getCheckedRadioButtonId()-200);
                saveUiState();renderAll();Toast.makeText(this,"Đã lưu cài đặt",Toast.LENGTH_SHORT).show();
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

    void showShareChooser(){
        TableModel t=selected();if(t==null)return;
        GroupModel currentGroup=null;
        if(t.groupId!=null&&!t.groupId.isEmpty()){
            for(GroupModel g:groups)if(t.groupId.equals(g.id)){currentGroup=g;break;}
        }

        if(currentGroup==null){
            shareCurrent();
            return;
        }

        final GroupModel g=currentGroup;
        String[] opts={
            "📄 Chia sẻ bảng hiện tại",
            "📚 Chia sẻ cả nhóm \""+g.name+"\""
        };
        new AlertDialog.Builder(this)
            .setTitle("Chia sẻ ảnh")
            .setItems(opts,(d,i)->{
                if(i==0)shareCurrent();
                else shareGroup(g);
            })
            .setNegativeButton("Hủy",null)
            .show();
    }

    void shareCurrent(){
        TableModel t=selected();if(t==null)return;
        shareViewAsPng(buildShareView(t),"bang","Chia sẻ ảnh bảng");
    }

    void shareGroup(GroupModel g){
        if(g==null)return;
        ArrayList<TableModel> list=inGroup(g.id);
        if(list.isEmpty()){
            Toast.makeText(this,"Nhóm chưa có bảng",Toast.LENGTH_SHORT).show();
            return;
        }
        shareViewAsPng(buildGroupShareView(g),"nhom","Chia sẻ ảnh nhóm");
    }

    void shareViewAsPng(View report,String prefix,String chooserTitle){
        try{
            int width=Math.min(dp(900),Math.max(dp(560),getResources().getDisplayMetrics().widthPixels));
            report.measure(
                View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
            );
            report.layout(0,0,width,report.getMeasuredHeight());

            int height=Math.max(1,report.getMeasuredHeight());
            // Tránh OOM nếu nhóm có rất nhiều dòng.
            if(height>dp(12000)){
                Toast.makeText(this,"Ảnh nhóm quá dài. Hãy chia sẻ từng bảng hoặc giảm số dòng trống.",Toast.LENGTH_LONG).show();
                return;
            }

            Bitmap bmp=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(bmp);c.drawColor(Color.WHITE);report.draw(c);
            File dir=new File(getCacheDir(),"share");dir.mkdirs();
            File f=new File(dir,prefix+"-"+System.currentTimeMillis()+".png");
            try(FileOutputStream os=new FileOutputStream(f)){bmp.compress(Bitmap.CompressFormat.PNG,100,os);}
            bmp.recycle();

            Uri uri=Uri.parse("content://com.vinh.listcalculatorfold2.share/"+Uri.encode(f.getName()));
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("image/png");
            send.putExtra(Intent.EXTRA_STREAM,uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send,chooserTitle));
        }catch(Exception e){
            Toast.makeText(this,"Không tạo được ảnh chia sẻ",Toast.LENGTH_LONG).show();
        }
    }

    LinearLayout buildGroupShareView(GroupModel g){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28),dp(24),dp(28),dp(28));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        try{
            ImageView logo=new ImageView(this);
            logo.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
            titleRow.addView(logo,new LinearLayout.LayoutParams(dp(50),dp(50)));
        }catch(Exception ignored){}

        LinearLayout titleBox=new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12),0,0,0);
        TextView h=text(g.name,25,true);h.setTextColor(Color.BLACK);
        TextView sub=text(inGroup(g.id).size()+" bảng • Tổng nhóm "+fmt(groupTotal(g.id)),13,true);
        sub.setTextColor(accent);
        titleBox.addView(h);
        titleBox.addView(sub);
        titleRow.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(titleRow);

        TextView dt=text("Chia sẻ nhóm • "+new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date()),12,false);
        dt.setTextColor(Color.GRAY);
        root.addView(dt);
        root.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(16)));

        ArrayList<TableModel> list=inGroup(g.id);
        int pos=0;
        for(TableModel t:list){
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14),dp(12),dp(14),dp(14));
            GradientDrawable cardBg=new GradientDrawable();
            cardBg.setColor(Color.rgb(250,251,253));
            cardBg.setStroke(dp(1),Color.rgb(226,232,240));
            cardBg.setCornerRadius(dp(14));
            card.setBackground(cardBg);

            LinearLayout th=new LinearLayout(this);th.setGravity(Gravity.CENTER_VERTICAL);
            TextView name=text((pos+1)+". "+t.title,18,true);name.setTextColor(ink);
            TextView type=text("cancel".equals(t.type)?"HỦY VÉ":"BẢNG TÍNH",10,true);
            type.setTextColor("cancel".equals(t.type)?Color.rgb(194,65,12):accent);
            type.setGravity(Gravity.CENTER);
            th.addView(name,new LinearLayout.LayoutParams(0,dp(34),1));
            th.addView(type,new LinearLayout.LayoutParams(dp(82),dp(30)));
            card.addView(th);

            if("cancel".equals(t.type)){
                LinearLayout hh=shareRow();
                hh.addView(shareCell("Tên đại lý",true,Gravity.START),w(0,dp(38),3));
                hh.addView(shareCell("Số lượng",true,Gravity.END),w(0,dp(38),1));
                card.addView(hh);
                for(CancelRow x:t.cancelRows){
                    if(shareHideBlank&&x.blank())continue;
                    LinearLayout rr=shareRow();
                    rr.addView(shareCell(x.agent,false,Gravity.START),w(0,dp(36),3));
                    rr.addView(shareCell(fmt(x.qty),false,Gravity.END),w(0,dp(36),1));
                    card.addView(rr);
                }
            }else{
                LinearLayout hh=shareRow();
                hh.addView(shareCell("Đơn giá",true,Gravity.END),w(0,dp(38),2));
                hh.addView(shareCell("SL",true,Gravity.END),w(0,dp(38),1));
                hh.addView(shareCell("Thành tiền",true,Gravity.END),w(0,dp(38),2));
                card.addView(hh);
                for(CalcRow x:t.calcRows){
                    if(shareHideBlank&&x.blank())continue;
                    LinearLayout rr=shareRow();
                    rr.addView(shareCell(fmt(x.price),false,Gravity.END),w(0,dp(36),2));
                    rr.addView(shareCell(fmt(x.qty),false,Gravity.END),w(0,dp(36),1));
                    rr.addView(shareCell(fmt(x.price*x.qty),false,Gravity.END),w(0,dp(36),2));
                    card.addView(rr);
                }
            }

            TextView total=text("Tổng bảng: "+fmt(t.total()),16,true);
            total.setTextColor(accent);
            total.setGravity(Gravity.END);
            total.setPadding(0,dp(10),0,0);
            card.addView(total);
            root.addView(card,new LinearLayout.LayoutParams(-1,-2));

            if(pos<list.size()-1)root.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(12)));
            pos++;
        }

        LinearLayout groupTotalBox=new LinearLayout(this);
        groupTotalBox.setGravity(Gravity.CENTER_VERTICAL);
        groupTotalBox.setPadding(dp(14),dp(12),dp(14),dp(12));
        GradientDrawable totalBg=new GradientDrawable();
        totalBg.setColor(Color.rgb(239,246,255));
        totalBg.setCornerRadius(dp(14));
        groupTotalBox.setBackground(totalBg);
        TextView lbl=text("TỔNG NHÓM",15,true);lbl.setTextColor(ink);
        TextView val=text(fmt(groupTotal(g.id)),25,true);val.setTextColor(accent);val.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        groupTotalBox.addView(lbl,new LinearLayout.LayoutParams(0,dp(44),1));
        groupTotalBox.addView(val,new LinearLayout.LayoutParams(0,dp(44),1.4f));
        root.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(14)));
        root.addView(groupTotalBox);

        return root;
    }

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

    void save(){
        saveHandler.removeCallbacksAndMessages("SAVE");
        saveScheduled=true;
        saveHandler.postAtTime(()->saveNow(),"SAVE",SystemClock.uptimeMillis()+180);
    }
    void saveNow(){try{JSONObject root=new JSONObject();JSONArray ga=new JSONArray();for(GroupModel g:groups)ga.put(g.json());JSONArray ta=new JSONArray();for(TableModel t:tables)ta.put(t.json());root.put("groups",ga).put("tables",ta);getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(DATA,root.toString()).apply();saveUiState();saveScheduled=false;}catch(Exception ignored){saveScheduled=false;}}
    void flushSave(){
        if(saveScheduled)saveNow();
    }

    @Override protected void onPause(){
        flushSave();
        super.onPause();
    }

    void load(){String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(DATA,null);if(s!=null){try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++)tables.add(TableModel.from(ta.getJSONObject(i)));return;}catch(Exception ignored){}}migrateOld();}
    void migrateOld(){String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(OLD_DATA,null);if(s==null)return;try{JSONObject o=new JSONObject(s);JSONArray ga=o.optJSONArray("groups");if(ga!=null)for(int i=0;i<ga.length();i++)groups.add(GroupModel.from(ga.getJSONObject(i)));JSONArray ta=o.optJSONArray("tables");if(ta!=null)for(int i=0;i<ta.length();i++){JSONObject x=ta.getJSONObject(i);TableModel t=new TableModel();t.id=x.optString("id",id());t.type=x.optString("type","calc");t.title=x.optString("title","Bảng");t.groupId=x.optString("groupId","");t.updated=System.currentTimeMillis();if("cancel".equals(t.type)){JSONArray c=x.optJSONArray("cancelRows");if(c!=null)for(int j=0;j<c.length();j++){JSONObject z=c.optJSONObject(j);t.cancelRows.add(new CancelRow(z.optString("agent"),z.optLong("qty")));}}else{JSONArray v=x.optJSONArray("values");if(v!=null)for(int j=0;j<v.length();j++){CalcRow cr=new CalcRow();cr.price=v.optDouble(j);cr.qty=1;t.calcRows.add(cr);}}tables.add(t);}save();}catch(Exception ignored){}}

    TableModel selected(){return findTable(selectedId);}TableModel findTable(String id){if(id==null)return null;for(TableModel t:tables)if(id.equals(t.id))return t;return null;}ArrayList<TableModel> inGroup(String gid){ArrayList<TableModel> a=new ArrayList<>();for(TableModel t:tables)if(gid.equals(t.groupId))a.add(t);return a;}double groupTotal(String gid){double x=0;for(TableModel t:tables)if(gid.equals(t.groupId))x+=t.total();return x;}

    static class GroupModel{
        String id,name;
        boolean pinned=false;
        long created=System.currentTimeMillis();
        JSONObject json()throws Exception{
            return new JSONObject().put("id",id).put("name",name).put("pinned",pinned).put("created",created);
        }
        static GroupModel from(JSONObject o){
            GroupModel g=new GroupModel();
            g.id=o.optString("id");g.name=o.optString("name","Nhóm");g.pinned=o.optBoolean("pinned",false);
            g.created=o.has("created")?o.optLong("created",0):0;
            return g;
        }
    }
    static class CalcRow{double price,qty;boolean blank(){return price==0&&qty==0;}JSONObject json()throws Exception{return new JSONObject().put("price",price).put("qty",qty);}static CalcRow from(JSONObject o){CalcRow r=new CalcRow();r.price=o.optDouble("price");r.qty=o.optDouble("qty");return r;}}
    static class CancelRow{String agent;long qty;CancelRow(String a,long q){agent=a;qty=q;}boolean blank(){return (agent==null||agent.trim().isEmpty())&&qty==0;}JSONObject json()throws Exception{return new JSONObject().put("agent",agent).put("qty",qty);}static CancelRow from(JSONObject o){return new CancelRow(o.optString("agent"),o.optLong("qty"));}}
    static class TableModel{String id,type="calc",title="Bảng",groupId="";long created=System.currentTimeMillis(),updated=created;boolean locked=false;ArrayList<CalcRow> calcRows=new ArrayList<>();ArrayList<CancelRow> cancelRows=new ArrayList<>();double total(){double x=0;if("cancel".equals(type)){for(CancelRow r:cancelRows)x+=r.qty;}else for(CalcRow r:calcRows)x+=r.price*r.qty;return x;}int dataRowCount(){int n=0;if("cancel".equals(type)){for(CancelRow r:cancelRows)if(!r.blank())n++;}else for(CalcRow r:calcRows)if(!r.blank())n++;return n;}JSONObject json()throws Exception{JSONObject o=new JSONObject().put("id",id).put("type",type).put("title",title).put("groupId",groupId).put("created",created).put("updated",updated).put("locked",locked);JSONArray a=new JSONArray();for(CalcRow r:calcRows)a.put(r.json());o.put("calcRows",a);JSONArray c=new JSONArray();for(CancelRow r:cancelRows)c.put(r.json());o.put("cancelRows",c);return o;}static TableModel from(JSONObject o){TableModel t=new TableModel();t.id=o.optString("id");t.type=o.optString("type","calc");t.title=o.optString("title","Bảng");t.groupId=o.optString("groupId","");
        t.updated=o.optLong("updated",System.currentTimeMillis());
        t.created=o.has("created")?o.optLong("created",t.updated):t.updated;
        t.locked=o.optBoolean("locked",false);JSONArray a=o.optJSONArray("calcRows");if(a!=null)for(int i=0;i<a.length();i++)t.calcRows.add(CalcRow.from(a.optJSONObject(i)));JSONArray c=o.optJSONArray("cancelRows");if(c!=null)for(int i=0;i<c.length();i++)t.cancelRows.add(CancelRow.from(c.optJSONObject(i)));return t;}}

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
        final int row=Math.max(0,pendingScrollRow>=0?pendingScrollRow:activeRow);

        gridRecycler.post(()->{
            RecyclerView.LayoutManager baseLm=gridRecycler.getLayoutManager();
            if(!(baseLm instanceof LinearLayoutManager)){
                gridRecycler.scrollToPosition(row);
                pendingScrollRow=-1;
                return;
            }

            LinearLayoutManager lm=(LinearLayoutManager)baseLm;
            int first=lm.findFirstVisibleItemPosition();
            int lastComplete=lm.findLastCompletelyVisibleItemPosition();

            // RecyclerView vừa dựng xong: chỉ đưa dòng đầu về đầu, không tự kéo các dòng khác lên.
            if(first==RecyclerView.NO_POSITION){
                if(row==0)lm.scrollToPositionWithOffset(0,0);
                pendingScrollRow=-1;
                return;
            }

            // Dòng đang sửa nằm phía trên vùng nhìn thấy -> đưa vừa đủ lên màn hình.
            if(row<first){
                lm.scrollToPositionWithOffset(row,dp(4));
            }
            // Chỉ cuộn khi dòng nhập đã vượt quá dòng cuối còn nhìn thấy trọn vẹn.
            // Dòng mới sẽ nằm sát đáy, nhờ vậy vẫn giữ được tối đa các dòng đầu phía trên.
            else if(lastComplete!=RecyclerView.NO_POSITION && row>lastComplete){
                int rowHeight=dp(dataRowDp());
                View sample=lm.findViewByPosition(lastComplete);
                if(sample!=null && sample.getHeight()>0)rowHeight=sample.getHeight();

                int bottomOffset=Math.max(dp(4),gridRecycler.getHeight()-rowHeight-dp(4));
                lm.scrollToPositionWithOffset(row,bottomOffset);
            }
            // Nếu dòng vẫn đang nằm trong vùng nhìn thấy thì giữ nguyên vị trí bảng.

            pendingScrollRow=-1;
        });
    }

    LinearLayout gridRow(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setBaselineAligned(false);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(paper);
        return r;
    }TextView cell(String s,int sp,boolean bold,int gravity){if(compact)sp=Math.max(11,sp-2);TextView v=text(s,sp,bold);v.setGravity(gravity);int cw=getResources().getConfiguration().screenWidthDp;
        int cp=cw<380?5:(cw<600?7:(cw<840?7:9));
        v.setPadding(dp(cp),0,dp(cp),0);GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setStroke(dp(1),rule);v.setBackground(d);return v;}LinearLayout shareRow(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}TextView shareCell(String s,boolean bold,int gravity){TextView v=text(s,14,bold);v.setGravity(gravity|Gravity.CENTER_VERTICAL);v.setPadding(dp(8),0,dp(8),0);GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setStroke(1,Color.LTGRAY);v.setBackground(d);return v;}
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
