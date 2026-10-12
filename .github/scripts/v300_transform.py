from pathlib import Path
import re

p=Path("app/src/main/java/com/vinh/listcalculatorfold2/MainActivity.java")
s=p.read_text()

def rep(old,new,n=1):
    global s
    if old not in s:
        raise SystemExit("Missing anchor: "+old[:120].replace("\n"," "))
    s=s.replace(old,new,n)

# Route camera/gallery OCR through a crop/region picker first.
pat=r'Toast\.makeText\(this,"Đã nhận ảnh, đang đọc tờ hủy…",Toast\.LENGTH_SHORT\)\.show\(\);\s*runCancelSheetOcr\(uri\);'
repl='Toast.makeText(this,"Đã nhận ảnh • chọn vùng Vé hủy",Toast.LENGTH_SHORT).show();\\n                  showCancelRegionPicker(uri);'
s,n=re.subn(pat,repl,s,count=1)
if n!=1:
    raise SystemExit("Cannot route OCR through region picker")

marker='    void runCancelSheetOcr(Uri uri){'
if marker not in s:
    raise SystemExit("runCancelSheetOcr marker not found")

insert=r'''    Bitmap loadOcrBitmap(Uri uri){
        try{
            Bitmap bm;
            try(InputStream is=getContentResolver().openInputStream(uri)){
                bm=BitmapFactory.decodeStream(is);
            }
            if(bm==null)return null;

            int orientation=android.media.ExifInterface.ORIENTATION_NORMAL;
            try(InputStream eis=getContentResolver().openInputStream(uri)){
                android.media.ExifInterface exif=new android.media.ExifInterface(eis);
                orientation=exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_NORMAL);
            }catch(Exception ignored){}

            Matrix m=new Matrix();
            if(orientation==android.media.ExifInterface.ORIENTATION_ROTATE_90)m.postRotate(90);
            else if(orientation==android.media.ExifInterface.ORIENTATION_ROTATE_180)m.postRotate(180);
            else if(orientation==android.media.ExifInterface.ORIENTATION_ROTATE_270)m.postRotate(270);
            if(!m.isIdentity()){
                Bitmap r=Bitmap.createBitmap(bm,0,0,bm.getWidth(),bm.getHeight(),m,true);
                if(r!=bm)bm.recycle();
                bm=r;
            }

            int max=Math.max(bm.getWidth(),bm.getHeight());
            if(max>2400){
                float scale=2400f/max;
                Bitmap r=Bitmap.createScaledBitmap(bm,Math.max(1,(int)(bm.getWidth()*scale)),Math.max(1,(int)(bm.getHeight()*scale)),true);
                if(r!=bm)bm.recycle();
                bm=r;
            }
            return bm;
        }catch(Exception e){
            Toast.makeText(this,"Không đọc được ảnh: "+e.getMessage(),Toast.LENGTH_LONG).show();
            return null;
        }
    }

    class CancelCropView extends View{
        Bitmap bitmap;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF imageRect=new RectF(), cropRect=new RectF();
        float downX,downY,lastX,lastY;
        int dragMode=0; // 1 move, 2..5 corners, 6 new rectangle
        float density;

        CancelCropView(Context c,Bitmap b){
            super(c);bitmap=b;density=getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.rgb(18,18,18));
        }

        float handleRadius(){return 22*density;}
        float minSize(){return 70*density;}

        void resetCrop(){
            if(imageRect.width()<=0)return;
            float mx=imageRect.width()*.07f, my=imageRect.height()*.08f;
            cropRect.set(imageRect.left+mx,imageRect.top+my,imageRect.right-mx,imageRect.bottom-my);
            invalidate();
        }

        @Override protected void onSizeChanged(int w,int h,int ow,int oh){
            if(bitmap==null)return;
            float scale=Math.min(w/(float)bitmap.getWidth(),h/(float)bitmap.getHeight());
            float bw=bitmap.getWidth()*scale,bh=bitmap.getHeight()*scale;
            imageRect.set((w-bw)/2f,(h-bh)/2f,(w+bw)/2f,(h+bh)/2f);
            if(cropRect.isEmpty())resetCrop();
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(bitmap==null)return;
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);
            c.drawBitmap(bitmap,null,imageRect,paint);

            paint.setColor(0x88000000);
            c.drawRect(imageRect.left,imageRect.top,imageRect.right,cropRect.top,paint);
            c.drawRect(imageRect.left,cropRect.bottom,imageRect.right,imageRect.bottom,paint);
            c.drawRect(imageRect.left,cropRect.top,cropRect.left,cropRect.bottom,paint);
            c.drawRect(cropRect.right,cropRect.top,imageRect.right,cropRect.bottom,paint);

            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setColor(Color.rgb(11,87,208));
            c.drawRect(cropRect,paint);
            paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);
            for(float[] pt:new float[][]{{cropRect.left,cropRect.top},{cropRect.right,cropRect.top},{cropRect.left,cropRect.bottom},{cropRect.right,cropRect.bottom}})
                c.drawCircle(pt[0],pt[1],8*density,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setColor(Color.rgb(11,87,208));
            for(float[] pt:new float[][]{{cropRect.left,cropRect.top},{cropRect.right,cropRect.top},{cropRect.left,cropRect.bottom},{cropRect.right,cropRect.bottom}})
                c.drawCircle(pt[0],pt[1],8*density,paint);
        }

        int cornerAt(float x,float y){
            float r=handleRadius();
            if(Math.hypot(x-cropRect.left,y-cropRect.top)<r)return 2;
            if(Math.hypot(x-cropRect.right,y-cropRect.top)<r)return 3;
            if(Math.hypot(x-cropRect.left,y-cropRect.bottom)<r)return 4;
            if(Math.hypot(x-cropRect.right,y-cropRect.bottom)<r)return 5;
            return 0;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            float x=e.getX(),y=e.getY();
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                downX=lastX=x;downY=lastY=y;
                dragMode=cornerAt(x,y);
                if(dragMode==0)dragMode=cropRect.contains(x,y)?1:6;
                if(dragMode==6){
                    cropRect.set(clamp(x,imageRect.left,imageRect.right),clamp(y,imageRect.top,imageRect.bottom),
                                 clamp(x,imageRect.left,imageRect.right),clamp(y,imageRect.top,imageRect.bottom));
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                x=clamp(x,imageRect.left,imageRect.right);y=clamp(y,imageRect.top,imageRect.bottom);
                if(dragMode==1){
                    float dx=x-lastX,dy=y-lastY;
                    if(cropRect.left+dx<imageRect.left)dx=imageRect.left-cropRect.left;
                    if(cropRect.right+dx>imageRect.right)dx=imageRect.right-cropRect.right;
                    if(cropRect.top+dy<imageRect.top)dy=imageRect.top-cropRect.top;
                    if(cropRect.bottom+dy>imageRect.bottom)dy=imageRect.bottom-cropRect.bottom;
                    cropRect.offset(dx,dy);
                }else if(dragMode==2){
                    cropRect.left=Math.min(x,cropRect.right-minSize());cropRect.top=Math.min(y,cropRect.bottom-minSize());
                }else if(dragMode==3){
                    cropRect.right=Math.max(x,cropRect.left+minSize());cropRect.top=Math.min(y,cropRect.bottom-minSize());
                }else if(dragMode==4){
                    cropRect.left=Math.min(x,cropRect.right-minSize());cropRect.bottom=Math.max(y,cropRect.top+minSize());
                }else if(dragMode==5){
                    cropRect.right=Math.max(x,cropRect.left+minSize());cropRect.bottom=Math.max(y,cropRect.top+minSize());
                }else if(dragMode==6){
                    cropRect.set(Math.min(downX,x),Math.min(downY,y),Math.max(downX,x),Math.max(downY,y));
                }
                cropRect.left=clamp(cropRect.left,imageRect.left,imageRect.right);
                cropRect.right=clamp(cropRect.right,imageRect.left,imageRect.right);
                cropRect.top=clamp(cropRect.top,imageRect.top,imageRect.bottom);
                cropRect.bottom=clamp(cropRect.bottom,imageRect.top,imageRect.bottom);
                lastX=x;lastY=y;invalidate();return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL){
                dragMode=0;getParent().requestDisallowInterceptTouchEvent(false);return true;
            }
            return true;
        }

        float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}

        Bitmap selectedBitmap(){
            if(bitmap==null||imageRect.width()<=0||cropRect.width()<5||cropRect.height()<5)return bitmap;
            float sx=bitmap.getWidth()/imageRect.width(), sy=bitmap.getHeight()/imageRect.height();
            int l=(int)((cropRect.left-imageRect.left)*sx);
            int t=(int)((cropRect.top-imageRect.top)*sy);
            int r=(int)((cropRect.right-imageRect.left)*sx);
            int b=(int)((cropRect.bottom-imageRect.top)*sy);
            l=Math.max(0,Math.min(bitmap.getWidth()-1,l));
            t=Math.max(0,Math.min(bitmap.getHeight()-1,t));
            r=Math.max(l+1,Math.min(bitmap.getWidth(),r));
            b=Math.max(t+1,Math.min(bitmap.getHeight(),b));
            return Bitmap.createBitmap(bitmap,l,t,r-l,b-t);
        }
    }

    void showCancelRegionPicker(Uri uri){
        Bitmap bm=loadOcrBitmap(uri);
        if(bm==null){runCancelSheetOcr(uri);return;}

        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10),dp(4),dp(10),dp(8));

        TextView guide=text("Kéo khung xanh bao đúng vùng bảng/tỉnh cần nhập hủy. Có thể kéo 4 góc để chỉnh. Nếu ảnh có 2 tỉnh, chọn từng bảng một.",12,false);
        guide.setTextColor(muted);guide.setPadding(dp(4),0,dp(4),dp(6));
        box.addView(guide,new LinearLayout.LayoutParams(-1,dp(52)));

        CancelCropView crop=new CancelCropView(this,bm);
        int h=Math.min((int)(getResources().getDisplayMetrics().heightPixels*.62f),dp(foldInnerLayout?620:520));
        box.addView(crop,new LinearLayout.LayoutParams(-1,h));

        LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(8),0,0);
        Button reset=smallActionButton("Toàn ảnh");
        Button scan=largeActionButton("Quét vùng này");
        scan.setTextColor(accent);
        actions.addView(reset,new LinearLayout.LayoutParams(0,dp(48),.8f));
        actions.addView(scan,new LinearLayout.LayoutParams(0,dp(48),1.2f));
        box.addView(actions);

        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle("Chọn vùng nhập hủy")
            .setView(box)
            .setNegativeButton("Hủy",(d,w)->{if(!bm.isRecycled())bm.recycle();})
            .create();

        reset.setOnClickListener(v->{haptic(v);crop.resetCrop();});
        scan.setOnClickListener(v->{
            haptic(v);
            Bitmap selected=crop.selectedBitmap();
            dlg.dismiss();
            Toast.makeText(this,"Đang nhận dạng vùng đã chọn…",Toast.LENGTH_SHORT).show();
            runCancelSheetOcrBitmap(selected);
        });
        dlg.setOnCancelListener(d->{if(!bm.isRecycled())bm.recycle();});
        dlg.show();
    }

    void runCancelSheetOcrBitmap(Bitmap bitmap){
        if(bitmap==null){
            Toast.makeText(this,"Vùng chọn không hợp lệ",Toast.LENGTH_LONG).show();
            return;
        }
        if(ocrProgress!=null&&ocrProgress.isShowing())ocrProgress.dismiss();
        ocrProgress=ProgressDialog.show(this,"Đang đọc vùng tờ hủy","Nhận dạng tên đại lý và số Vé hủy…",true,false);
        InputImage image=InputImage.fromBitmap(bitmap,0);
        TextRecognizer recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
            .addOnSuccessListener(result->{
                try{
                    ArrayList<OcrSheet> sheets=parseCancelSheets(result,image.getWidth(),image.getHeight());
                    if(ocrProgress!=null)ocrProgress.dismiss();
                    if(sheets.isEmpty()){
                        OcrSheet fallback=buildFallbackOcrSheet(result,image.getWidth(),image.getHeight());
                        showOcrReviewWithRaw(fallback,result.getText(),true);
                    }else if(sheets.size()==1){
                        showOcrReviewWithRaw(sheets.get(0),result.getText(),false);
                    }else{
                        String[] names=new String[sheets.size()+1];
                        for(int i=0;i<sheets.size();i++)names[i]=(i+1)+". "+sheets.get(i).province+" • "+fmt(sheets.get(i).total());
                        names[sheets.size()]="Nhập tất cả thành các bảng hủy riêng";
                        new AlertDialog.Builder(this).setTitle("Tìm thấy "+sheets.size()+" bảng")
                            .setItems(names,(d,which)->{
                                if(which<sheets.size())showOcrReviewWithRaw(sheets.get(which),result.getText(),false);
                                else importAllOcrSheets(sheets);
                            }).show();
                    }
                }catch(Exception e){
                    if(ocrProgress!=null)ocrProgress.dismiss();
                    Toast.makeText(this,"Không phân tích được vùng: "+e.getMessage(),Toast.LENGTH_LONG).show();
                }finally{
                    recognizer.close();
                    if(!bitmap.isRecycled())bitmap.recycle();
                }
            })
            .addOnFailureListener(e->{
                if(ocrProgress!=null)ocrProgress.dismiss();
                recognizer.close();
                if(!bitmap.isRecycled())bitmap.recycle();
                Toast.makeText(this,"OCR vùng chọn lỗi: "+e.getMessage(),Toast.LENGTH_LONG).show();
            });
    }

'''
s=s.replace(marker,insert+marker,1)
s=s.replace("Phiên bản 2.9.9","Phiên bản 3.0.0")
p.write_text(s)
