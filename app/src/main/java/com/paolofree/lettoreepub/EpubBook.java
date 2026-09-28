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
        String css="<style>html{background:"+bg+"}body{color:"+fg+";font-family:serif;font-size:"+size+"px;line-height:1.65;padding:16px;max-width:800px;margin:auto}img{max-width:100%;height:auto}a{color:#35a7dc}</style>";
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
