package vn.puck.epubreader;

import android.content.Context;
import android.net.Uri;
import android.text.Html;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

final class EpubBook {
 static final class Chapter {
  final String title;
  final ArrayList<String> paragraphs;
  final ArrayList<Integer> segmentPositions=new ArrayList<>();
  Chapter(String title,ArrayList<String> paragraphs){this.title=title;this.paragraphs=paragraphs;}
 }
 static final class Segment {
  final int chapter;
  final int paragraph;
  final String text;
  Segment(int c,int p,String t){chapter=c;paragraph=p;text=t;}
 }
 String title="Sách EPUB";
 final ArrayList<Chapter> chapters=new ArrayList<>();
 final ArrayList<Segment> segments=new ArrayList<>();

 static EpubBook open(Context context,Uri uri)throws Exception{
  File dir=new File(context.getCacheDir(),"epubs");
  if(!dir.exists()&&!dir.mkdirs())throw new Exception("Không tạo được bộ nhớ tạm.");
  File temp=new File(dir,"reading.epub");
  long total=0;
  try(InputStream in=context.getContentResolver().openInputStream(uri);
      FileOutputStream out=new FileOutputStream(temp)){
   if(in==null)throw new Exception("Không mở được tệp EPUB.");
   byte[] buf=new byte[32768];int n;
   while((n=in.read(buf))!=-1){
    total+=n;
    if(total>80L*1024*1024)throw new Exception("EPUB vượt 80 MB (giới hạn bản thử).");
    out.write(buf,0,n);
   }
  }
  EpubBook book=new EpubBook();
  try(ZipFile zip=new ZipFile(temp)){
   Document container=parseXml(readEntry(zip,"META-INF/container.xml",250000));
   NodeList roots=container.getElementsByTagNameNS("*","rootfile");
   if(roots.getLength()==0)throw new Exception("EPUB không có rootfile trong container.xml.");
   String opf=((Element)roots.item(0)).getAttribute("full-path");
   if(opf.isEmpty())throw new Exception("Không tìm thấy đường dẫn OPF.");
   Document packageDoc=parseXml(readEntry(zip,opf,2000000));
   NodeList titles=packageDoc.getElementsByTagNameNS("*","title");
   if(titles.getLength()>0&&!titles.item(0).getTextContent().trim().isEmpty())
    book.title=titles.item(0).getTextContent().trim();

   Map<String,String> manifest=new HashMap<>();
   NodeList items=packageDoc.getElementsByTagNameNS("*","item");
   String base=opf.contains("/")?opf.substring(0,opf.lastIndexOf('/')+1):"";
   for(int i=0;i<items.getLength();i++){
    Element item=(Element)items.item(i);
    String href=item.getAttribute("href"),mime=item.getAttribute("media-type");
    if(!"application/xhtml+xml".equals(mime)&&!"text/html".equals(mime))continue;
    int hash=href.indexOf('#');if(hash>=0)href=href.substring(0,hash);
    String full=Paths.get(base).resolve(Uri.decode(href)).normalize().toString().replace('\\','/');
    if(full.startsWith("../")||full.startsWith("/"))continue;
    manifest.put(item.getAttribute("id"),full);
   }
   NodeList refs=packageDoc.getElementsByTagNameNS("*","itemref");
   int seenChars=0;
   for(int i=0;i<refs.getLength()&&book.chapters.size()<500;i++){
    Element ref=(Element)refs.item(i);
    String path=manifest.get(ref.getAttribute("idref"));
    if(path==null)continue;
    ZipEntry entry=zip.getEntry(path);
    if(entry==null)continue;
    String html;
    try{html=readEntry(zip,path,6000000);}catch(Exception ignored){continue;}
    ArrayList<String> ps=extractParagraphs(html);
    if(ps.isEmpty())continue;
    for(String p:ps)seenChars+=p.length();
    if(seenChars>5000000)throw new Exception("Sách quá dài đối với bản thử (5 triệu ký tự).");
    String chTitle=ps.get(0);
    if(chTitle.length()>70)chTitle="Chương "+(book.chapters.size()+1);
    book.chapters.add(new Chapter(chTitle,ps));
   }
  }
  if(book.chapters.isEmpty())throw new Exception("Không tìm thấy chương văn bản (EPUB có thể bị DRM hoặc định dạng đặc biệt).");
  book.buildSegments();
  if(book.segments.isEmpty())throw new Exception("Sách không có nội dung để đọc.");
  return book;
 }
 private void buildSegments(){
  for(int ci=0;ci<chapters.size();ci++){
   Chapter chapter=chapters.get(ci);
   StringBuilder buf=new StringBuilder();
   int startPara=0;
   for(int pi=0;pi<chapter.paragraphs.size();pi++){
    String para=chapter.paragraphs.get(pi).trim();
    if(para.isEmpty())continue;
    int max=chapter.segmentPositions.isEmpty()?230:520;
    if(buf.length()>0&&buf.length()+para.length()+1>max){
     addSegment(ci,startPara,buf.toString(),chapter);
     buf.setLength(0);
    }
    if(para.length()>520){
     if(buf.length()>0){
      addSegment(ci,startPara,buf.toString(),chapter);buf.setLength(0);
     }
     for(String part:splitLong(para,520)){
      addSegment(ci,pi,part,chapter);
     }
    }else{
     if(buf.length()==0)startPara=pi;else buf.append("\n");
     buf.append(para);
     if(chapter.segmentPositions.isEmpty()&&buf.length()>=175){
      addSegment(ci,startPara,buf.toString(),chapter);buf.setLength(0);
     }
    }
   }
   if(buf.length()>0)addSegment(ci,startPara,buf.toString(),chapter);
  }
 }
 private void addSegment(int chapter,int paragraph,String value,Chapter ch){
  if(value.trim().isEmpty())return;
  ch.segmentPositions.add(segments.size());
  segments.add(new Segment(chapter,paragraph,value.trim()));
 }
 private static List<String> splitLong(String text,int max){
  ArrayList<String> parts=new ArrayList<>();
  while(text.length()>max){
   int cut=-1;
   for(int i=max;i>max/2;i--){
    char c=text.charAt(i);
    if(c=='.'||c=='!'||c=='?'||c=='。'||c=='、'||c==','||c==';'){
     cut=i+1;break;
    }
   }
   if(cut<0)for(int i=max;i>max/2;i--)
    if(Character.isWhitespace(text.charAt(i))){cut=i;break;}
   if(cut<0)cut=max;
   parts.add(text.substring(0,cut).trim());
   text=text.substring(cut).trim();
  }
  if(!text.isEmpty())parts.add(text);
  return parts;
 }
 private static ArrayList<String> extractParagraphs(String html){
  ArrayList<String> ps=new ArrayList<>();
  html=html.replaceAll("(?is)<(script|style|nav|head)\\b[^>]*>.*?</\\1>"," ");
  int body=html.toLowerCase(Locale.ROOT).indexOf("<body");
  if(body>=0){
   int start=html.indexOf('>',body);
   if(start>=0)html=html.substring(start+1);
  }
  int end=html.toLowerCase(Locale.ROOT).lastIndexOf("</body");
  if(end>0)html=html.substring(0,end);
  String[] chunks=html.split("(?i)</(?:p|h[1-6]|div|li|blockquote)>|<br\\s*/?>");
  for(String ch:chunks){
   String value=Html.fromHtml(ch,Html.FROM_HTML_MODE_COMPACT).toString()
    .replace('\u00a0',' ').replaceAll("[\\t\\r ]+"," ")
    .replaceAll("\\n{3,}","\\n\\n").trim();
   if(value.length()>1&&!value.equals(" "))ps.add(value);
  }
  return ps;
 }
 private static String readEntry(ZipFile zip,String path,int limit)throws Exception{
  ZipEntry e=zip.getEntry(path);
  if(e==null)throw new Exception("EPUB thiếu tệp: "+path);
  if(e.getSize()>limit)throw new Exception("Nội dung EPUB quá lớn: "+path);
  try(InputStream in=zip.getInputStream(e);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buf=new byte[8192];int n;
   while((n=in.read(buf))!=-1){
    if(out.size()+n>limit)throw new Exception("Nội dung chương vượt giới hạn.");
    out.write(buf,0,n);
   }
   return out.toString("UTF-8");
  }
 }
 private static Document parseXml(String xml)throws Exception{
  DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
  f.setNamespaceAware(true);
  f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
  f.setFeature("http://xml.org/sax/features/external-general-entities",false);
  f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
  f.setXIncludeAware(false);
  return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
 }
}
