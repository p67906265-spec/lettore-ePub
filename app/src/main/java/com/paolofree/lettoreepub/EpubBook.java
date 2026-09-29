package com.paolofree.lettoreepub;

import android.text.Html;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Parser EPUB: spine, indice EPUB2/3, copertina e percorsi URL encoded. */
public final class EpubBook implements Closeable {
    public final File file;
    public String title="Libro senza titolo", author="", coverPath=null;
    public final ArrayList<String> chapters=new ArrayList<>(), labels=new ArrayList<>();
    private final ZipFile zip;
    private final Map<String,String> manifest=new HashMap<>();
    private String base="";

    public EpubBook(File source)throws Exception {
        file=source; zip=new ZipFile(source);
        Document container=xml(read("META-INF/container.xml"));
        Node root=container.getElementsByTagName("rootfile").item(0);
        if(root==null)throw new IOException("Struttura EPUB non valida");
        String opf=attr(root,"full-path"); int slash=opf.lastIndexOf('/');
        base=slash<0?"":opf.substring(0,slash+1);
        Document pkg=xml(read(opf));
        title=text(pkg,"dc:title",title).trim(); author=text(pkg,"dc:creator","").trim();
        String navPath=null,ncxPath=null,coverId=null;
        NodeList metas=pkg.getElementsByTagName("meta");
        for(int i=0;i<metas.getLength();i++){Element e=(Element)metas.item(i);if("cover".equalsIgnoreCase(e.getAttribute("name")))coverId=e.getAttribute("content");}
        NodeList items=pkg.getElementsByTagName("item");
        for(int i=0;i<items.getLength();i++){
            Element e=(Element)items.item(i);String id=e.getAttribute("id"),href=resolve(base,e.getAttribute("href"));
            manifest.put(id,href);String props=e.getAttribute("properties"),media=e.getAttribute("media-type");
            if(Arrays.asList(props.split("\\s+")).contains("nav"))navPath=href;
            if("application/x-dtbncx+xml".equals(media))ncxPath=href;
            if(props.contains("cover-image")||id.equals(coverId))coverPath=href;
        }
        NodeList refs=pkg.getElementsByTagName("itemref");
        for(int i=0;i<refs.getLength();i++){String href=manifest.get(attr(refs.item(i),"idref"));if(href!=null)chapters.add(href);}
        if(chapters.isEmpty())throw new IOException("Nessun capitolo trovato");
        for(int i=0;i<chapters.size();i++)labels.add("Capitolo "+(i+1));
        Map<String,String> toc=new LinkedHashMap<>();
        try{if(navPath!=null)readNav(navPath,toc);}catch(Exception ignored){}
        try{if(toc.isEmpty()&&ncxPath!=null)readNcx(ncxPath,toc);}catch(Exception ignored){}
        for(int i=0;i<chapters.size();i++)for(Map.Entry<String,String> e:toc.entrySet())
            if(stripFragment(e.getKey()).equals(stripFragment(chapters.get(i)))){String v=e.getValue().trim();if(!v.isEmpty())labels.set(i,v);break;}
    }

    private void readNav(String path,Map<String,String> out)throws Exception{
        Document d=xml(read(path));NodeList links=d.getElementsByTagName("a");String p=parent(path);
        for(int i=0;i<links.getLength();i++){Element a=(Element)links.item(i);String h=a.getAttribute("href");if(!h.isEmpty())out.put(resolve(p,h),a.getTextContent().replaceAll("\\s+"," "));}
    }
    private void readNcx(String path,Map<String,String> out)throws Exception{
        Document d=xml(read(path));NodeList points=d.getElementsByTagName("navPoint");String p=parent(path);
        for(int i=0;i<points.getLength();i++){Element n=(Element)points.item(i);NodeList c=n.getElementsByTagName("content"),t=n.getElementsByTagName("text");if(c.getLength()>0&&t.getLength()>0)out.put(resolve(p,attr(c.item(0),"src")),t.item(0).getTextContent());}
    }
    private static String parent(String p){int i=p.lastIndexOf('/');return i<0?"":p.substring(0,i+1);}
    private static String stripFragment(String p){int i=p.indexOf('#');return i<0?p:p.substring(0,i);}
    private static String attr(Node n,String name){Node a=n.getAttributes().getNamedItem(name);return a==null?"":a.getNodeValue();}
    private static String text(Document d,String tag,String def){NodeList n=d.getElementsByTagName(tag);return n.getLength()>0?n.item(0).getTextContent():def;}
    private static Document xml(byte[] bytes)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);f.setExpandEntityReferences(false);
        safeFeature(f,"http://apache.org/xml/features/disallow-doctype-decl",true);
        safeFeature(f,"http://xml.org/sax/features/external-general-entities",false);
        safeFeature(f,"http://xml.org/sax/features/external-parameter-entities",false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static void safeFeature(DocumentBuilderFactory f,String name,boolean value){try{f.setFeature(name,value);}catch(Exception ignored){}}
    private byte[] read(String path)throws IOException{ZipEntry e=zip.getEntry(normalize(path));if(e==null)throw new FileNotFoundException(path);try(InputStream in=zip.getInputStream(e);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static String decode(String p){try{return URLDecoder.decode(p.replace("+","%2B"),"UTF-8");}catch(Exception e){return p;}}
    private static String resolve(String parent,String href){href=decode(href);if(href.matches("^[a-zA-Z]+:.*"))return href;return normalize(parent+href);}
    private static String normalize(String p){p=stripFragment(p).replace('\\','/');Deque<String>s=new ArrayDeque<>();for(String x:p.split("/")){if(x.isEmpty()||".".equals(x))continue;if("..".equals(x)){if(!s.isEmpty())s.removeLast();}else s.addLast(x);}return String.join("/",s);}

    public String html(int index,String theme,int size,int margin)throws IOException{
        String raw=new String(read(chapters.get(index)),StandardCharsets.UTF_8)
                .replaceAll("(?is)<script[^>]*>.*?</script>","")
                .replaceAll("(?is)<iframe[^>]*>.*?</iframe>","")
                .replaceAll("(?i)\\son[a-z]+\\s*=\\s*(['\"]).*?\\1","");
        String bg="#fffaf0",fg="#27221d";
        if("Bianco".equals(theme)||"Chiaro".equals(theme)){bg="#ffffff";fg="#202124";}
        else if("Sabbia".equals(theme)){bg="#f1e3c6";fg="#34291e";}
        else if("Verde salvia".equals(theme)){bg="#dfe9dd";fg="#243127";}
        else if("Blu notte".equals(theme)){bg="#0b1f2d";fg="#e3edf3";}
        else if("Nero".equals(theme)||"Scuro".equals(theme)){bg="#101010";fg="#eeeeee";}
        String css="<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\"><style id=\"reader-style\">html{background:"+bg+"!important}body{color:"+fg+"!important;font-family:var(--reader-font,Georgia),serif!important;font-size:"+size+"px!important;line-height:1.55!important;max-width:none!important;margin:0!important;padding:24px "+margin+"px 40px!important;text-align:left!important;overflow-wrap:anywhere!important}p{display:block!important;text-align:left!important;text-indent:0!important;word-spacing:normal!important;letter-spacing:normal!important;margin:.55em 0 .8em!important}h1,h2,h3,h4,h5,h6{page-break-after:avoid!important;text-align:left!important;line-height:1.2!important}img{display:block!important;max-width:100%!important;max-height:58vh!important;width:auto!important;height:auto!important;object-fit:contain!important;margin:.35em auto!important}figure{max-width:100%!important;margin:.35em auto .8em!important}pre,code{white-space:pre-wrap!important;overflow-wrap:anywhere!important;font-size:.78em!important}table{max-width:100%!important;font-size:.78em!important;table-layout:fixed!important}a{color:#35a7dc!important}.tts-current{background:#ffd75a!important;color:#17212b!important;border-radius:4px}</style>";
        int h=raw.toLowerCase(Locale.ROOT).indexOf("</head>");return h>=0?raw.substring(0,h)+css+raw.substring(h):css+raw;
    }
    public String plain(int i)throws IOException{
        String raw=new String(read(chapters.get(i)),StandardCharsets.UTF_8)
                .replaceAll("(?is)<(script|style|title|nav)[^>]*>.*?</\\1>"," ")
                .replaceAll("(?is)<(sup|aside)[^>]*>.*?</\\1>"," ");
        return Html.fromHtml(raw,Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0',' ').replaceAll("[ \\t]+"," ").replaceAll("\\n{3,}","\\n\\n").trim();
    }
    public File extractTo(File destination)throws IOException{
        File marker=new File(destination,".complete-"+file.length()+"-"+file.lastModified());if(marker.exists())return destination;
        destination.mkdirs();Enumeration<? extends ZipEntry> es=zip.entries();String root=destination.getCanonicalPath()+File.separator;
        while(es.hasMoreElements()){ZipEntry e=es.nextElement();File o=new File(destination,decode(e.getName()));if(!o.getCanonicalPath().startsWith(root))continue;if(e.isDirectory()){o.mkdirs();continue;}if(o.getParentFile()!=null)o.getParentFile().mkdirs();try(InputStream in=zip.getInputStream(e);OutputStream out=new FileOutputStream(o)){byte[]b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}}
        marker.createNewFile();return destination;
    }
    public File chapterFile(File extracted,int i){return new File(extracted,chapters.get(i));}
    public File coverFile(File extracted){return coverPath==null?null:new File(extracted,coverPath);}
    public byte[] coverBytes(){try{return coverPath==null?null:read(coverPath);}catch(Exception e){return null;}}
    public String progressLabel(int index){
        if(labels.isEmpty())return "APRI IL LIBRO";index=Math.max(0,Math.min(index,labels.size()-1));String current=labels.get(index).trim();Integer number=chapterNumber(current);
        if(number!=null){int last=number;for(String label:labels){Integer n=chapterNumber(label);if(n!=null)last=Math.max(last,n);}return "CAPITOLO "+number+(last>number?" DI "+last:"");}
        if(current.toLowerCase(Locale.ITALIAN).startsWith("capitolo "))return current.toUpperCase(Locale.ITALIAN);
        return current.isEmpty()?"APRI IL LIBRO":current.toUpperCase(Locale.ITALIAN);
    }
    private static Integer chapterNumber(String label){try{java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)^(?:(?:capitolo|chapter)\\s*)?(\\d+)\\b").matcher(label.trim());return m.find()?Integer.parseInt(m.group(1)):null;}catch(Exception ignored){return null;}}
    public int findChapter(String path){String n=normalize(decode(path));for(int i=0;i<chapters.size();i++)if(n.endsWith(chapters.get(i)))return i;return -1;}
    @Override public void close()throws IOException{zip.close();}
}
