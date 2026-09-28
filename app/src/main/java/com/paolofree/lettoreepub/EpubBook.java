package com.paolofree.lettoreepub;

import android.text.Html;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public class EpubBook implements Closeable {
    public final File file; public String title="Libro senza titolo", author="";
    public final ArrayList<String> chapters=new ArrayList<>(), labels=new ArrayList<>();
    private final ZipFile zip; private String base="";

    public EpubBook(File f) throws Exception {
        file=f; zip=new ZipFile(f);
        Document container=xml(read("META-INF/container.xml"));
        String opf=container.getElementsByTagName("rootfile").item(0).getAttributes().getNamedItem("full-path").getNodeValue();
        int slash=opf.lastIndexOf('/'); base=slash<0?"":opf.substring(0,slash+1);
        Document pkg=xml(read(opf));
        title=text(pkg,"dc:title",title); author=text(pkg,"dc:creator","");
        HashMap<String,String> manifest=new HashMap<>(); NodeList items=pkg.getElementsByTagName("item");
        for(int i=0;i<items.getLength();i++){ Element e=(Element)items.item(i); manifest.put(e.getAttribute("id"),e.getAttribute("href")); }
        NodeList refs=pkg.getElementsByTagName("itemref");
        for(int i=0;i<refs.getLength();i++){String href=manifest.get(((Element)refs.item(i)).getAttribute("idref")); if(href!=null){chapters.add(normalize(base+href));labels.add("Capitolo "+(labels.size()+1));}}
        if(chapters.isEmpty()) throw new IOException("Nessun capitolo trovato");
    }
    private static String text(Document d,String tag,String def){NodeList n=d.getElementsByTagName(tag);return n.getLength()>0?n.item(0).getTextContent():def;}
    private static Document xml(byte[] b)throws Exception{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(false);return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));}
    private byte[] read(String path)throws IOException{ZipEntry e=zip.getEntry(normalize(path));if(e==null)throw new FileNotFoundException(path);try(InputStream in=zip.getInputStream(e);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();}}
    private static String normalize(String p){try{return new java.net.URI(null,null,p,null).normalize().getPath();}catch(Exception e){return p;}}
    public String html(int i,String theme,int size)throws IOException{
        String raw=new String(read(chapters.get(i)),StandardCharsets.UTF_8);
        String bg="#fffaf0",fg="#27221d";if("Scuro".equals(theme)){bg="#071521";fg="#eef6fa";}else if("Chiaro".equals(theme)){bg="#ffffff";fg="#202124";}
        String css="<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\"><style>"
                +"html{background:"+bg+"!important;}"
                +"body{color:"+fg+"!important;font-family:Georgia,serif!important;font-size:"+size+"px!important;line-height:1.55!important;max-width:none!important;margin:0!important;text-align:left!important;overflow-wrap:anywhere!important;word-break:normal!important;hyphens:auto!important;}"
                +"p{display:block!important;text-align:left!important;text-indent:0!important;word-spacing:normal!important;letter-spacing:normal!important;margin:.55em 0 .8em!important;padding:0!important;}"
                +"h1,h2,h3,h4,h5,h6{break-after:avoid-column!important;page-break-after:avoid!important;text-align:left!important;word-spacing:normal!important;letter-spacing:normal!important;margin:.25em 0 .65em!important;padding:0!important;line-height:1.2!important;}"
                +"img{display:block!important;max-width:100%!important;max-height:58vh!important;width:auto!important;height:auto!important;object-fit:contain!important;margin:.35em auto!important;break-inside:avoid-column!important;}"
                +"figure,.figure,.image,.illustration{max-width:100%!important;margin:.35em auto .8em!important;padding:0!important;break-inside:avoid-column!important;}"
                +"figcaption,.caption{font-size:.82em!important;line-height:1.25!important;text-align:center!important;margin:.25em 0 .6em!important;}"
                +"pre,code{white-space:pre-wrap!important;overflow-wrap:anywhere!important;word-break:break-word!important;font-size:.78em!important;}"
                +"table{max-width:100%!important;font-size:.78em!important;table-layout:fixed!important;}td,th{overflow-wrap:anywhere!important;}"
                +"body>div,body>section,body>article{max-width:100%!important;padding-top:0!important;margin-top:0!important;}"
                +"a{color:#35a7dc!important;}"
                +"</style>";
        int h=raw.toLowerCase().indexOf("</head>"); return h>=0?raw.substring(0,h)+css+raw.substring(h):css+raw;
    }
    public String plain(int i)throws IOException{return Html.fromHtml(new String(read(chapters.get(i)),StandardCharsets.UTF_8),Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\n{3,}","\n\n").trim();}
    public File extractTo(File destination)throws IOException{
        destination.mkdirs();
        Enumeration<? extends ZipEntry> entries=zip.entries();
        String root=destination.getCanonicalPath()+File.separator;
        while(entries.hasMoreElements()){
            ZipEntry entry=entries.nextElement();
            File output=new File(destination,entry.getName());
            String canonical=output.getCanonicalPath();
            if(!canonical.startsWith(root))continue;
            if(entry.isDirectory()){output.mkdirs();continue;}
            File parent=output.getParentFile();if(parent!=null)parent.mkdirs();
            try(InputStream in=zip.getInputStream(entry);OutputStream out=new FileOutputStream(output)){
                byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))>0)out.write(buffer,0,count);
            }
        }
        return destination;
    }
    public File chapterFile(File extracted,int i){return new File(extracted,chapters.get(i));}
    public void close()throws IOException{zip.close();}
}
