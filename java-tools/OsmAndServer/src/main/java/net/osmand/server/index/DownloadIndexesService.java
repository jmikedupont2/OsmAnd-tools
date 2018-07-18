package net.osmand.server.index;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DownloadIndexesService  {
	
	private static final Log LOGGER = LogFactory.getLog(DownloadIndexesService.class);

	private static final String INDEX_FILE = "new_indexes.xml";
	
	@Value("${download.indexes}")
    private String pathToDownloadFiles = "/var/www-download/";

	
	// 15 minutes
	@Scheduled(fixedDelay = 1000 * 60 * 15)
	public void checkOsmAndLiveStatus() {
		generateStandardIndexFile();
	}
	
	public List<DownloadIndex> loadDownloadIndexes() {
		List<DownloadIndex> list = new ArrayList<DownloadIndex>();
		File rootFolder = new File(pathToDownloadFiles);
		loadIndexesFromDir(list, rootFolder, "indexes", DownloadType.MAP);
		loadIndexesFromDir(list, rootFolder, ".", DownloadType.MAP);
		loadIndexesFromDir(list, rootFolder, "indexes", DownloadType.VOICE);
		loadIndexesFromDir(list, rootFolder, "indexes/fonts", DownloadType.FONTS);
		loadIndexesFromDir(list, rootFolder, "indexes/inapp/depth", DownloadType.DEPTH);
		loadIndexesFromDir(list, rootFolder, "wiki", DownloadType.WIKI_MAP);
		loadIndexesFromDir(list, rootFolder, "wikivoyage", DownloadType.WIKIVOYAGE);
		loadIndexesFromDir(list, rootFolder, "road-indexes", DownloadType.ROAD_MAP);
		loadIndexesFromDir(list, rootFolder, "srtm-countries", DownloadType.SRTM_MAP);
		loadIndexesFromDir(list, rootFolder, "hillshade", DownloadType.HILLSHADE);
		return list;
	}
	
	public File getIndexesXml(boolean upd, boolean gzip) {
		File target = getStandardFilePath(gzip);
		if(target.exists() || upd) {
			generateStandardIndexFile();
		}
		return target;
	}

	private File getStandardFilePath(boolean gzip) {
		return new File(pathToDownloadFiles, gzip ? INDEX_FILE + ".gz" : INDEX_FILE);
	}
	
	private synchronized void generateStandardIndexFile() {
		long start = System.currentTimeMillis();
		List<DownloadIndex> di = loadDownloadIndexes();
		File target = getStandardFilePath(false);
		generateIndexesFile(di, target, start);
		File gzip = getStandardFilePath(true);
		gzipFile(target, gzip);
		LOGGER.info(String.format("Regenerate indexes.xml in %.1f seconds",
				((System.currentTimeMillis() - start) / 1000.0)));
	}

	private void gzipFile(File target, File gzip) {
		try {
			FileInputStream is = new FileInputStream(target);
			GZIPOutputStream out = new GZIPOutputStream(new FileOutputStream(gzip));
			Algorithms.streamCopy(is, out);
			is.close();
			out.close();
		} catch (IOException e) {
			LOGGER.error("Gzip file " + target.getName(), e);
			e.printStackTrace();
		}
	}

	private void loadIndexesFromDir(List<DownloadIndex> list, File rootFolder, String subPath, DownloadType tp) {
		File subFolder = new File(rootFolder, subPath);
		File[] files = subFolder.listFiles();
		if(files == null || files.length == 0) {
			return;
		}
		for(File lf : files) {
			if(tp.acceptFile(lf)) {
				// TODO set proper name parse from file name (exclude _2, _ext_2), replace 
				// TODO set proper description (as first comment from zip file)
				String name = lf.getName();
				name = name.substring(0, name.indexOf('.'));
				name = name.replace('_', ' ');
				DownloadIndex di = new DownloadIndex(name, lf, tp);
				if (di.isZip()) {
					try {
						ZipFile zipFile = new ZipFile(lf);
						long contentSize = zipFile.stream().mapToLong(ZipEntry::getSize).sum();
						di.setContentSize(contentSize);
						zipFile.close();
					} catch (Exception e) {
						LOGGER.error(lf.getName() + ": " + e.getMessage(), e);
						e.printStackTrace();
					}
				}
				if(di.isValid()) {
					list.add(di);
				}
			}
		}
	}


	private void generateIndexesFile(List<DownloadIndex> indexes, File file, long start) {
		XMLStreamWriter writer = null;
		FileOutputStream fous = null;
		try {
			fous = new FileOutputStream(file);
			XMLOutputFactory factory = XMLOutputFactory.newInstance();
			writer = factory.createXMLStreamWriter(fous);
			writer.writeStartDocument();
			writer.writeCharacters("\n");
			writer.writeStartElement("osmand_regions");
			writer.writeAttribute("mapversion", "1");
			writer.writeAttribute("gentime", String.format("%.1f",
					((System.currentTimeMillis() - start) / 1000.0)) );
			for (DownloadIndex di : indexes) {
				di.writeType(writer);
			}
			writer.writeCharacters("\n");
			writer.writeEndElement();
			writer.writeEndDocument();
			writer.flush();
		} catch (Exception ex) {
			LOGGER.error(ex.getMessage(), ex);
			ex.printStackTrace();
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (XMLStreamException ex) {
				}
			}
			if (fous != null) {
				try {
					fous.close();
				} catch (IOException ex) {
				}
			}
		}
	}
	
	public enum DownloadType {
	    MAP ("region"),
	    VOICE ("region"),
	    DEPTH ("inapp"),
	    FONTS ("fonts"),
	    WIKI_MAP ("wiki"),
	    WIKIVOYAGE ("wikivoyage"),
	    ROAD_MAP ("road_region"),
	    HILLSHADE ("hillshade"),
	    SRTM_MAP ("srtmcountry");

	    private final String xmlTag;

		private DownloadType(String xmlTag) {
			this.xmlTag = xmlTag;
	    }
		
		public String getXmlTag() {
			return xmlTag;
		}

		public boolean acceptFile(File f) {
			switch (this) {
			case MAP:
			case ROAD_MAP:
			case WIKI_MAP:
			case DEPTH:
			case SRTM_MAP:
				return f.getName().endsWith(".obf.zip") || f.getName().endsWith(".obf");
			case WIKIVOYAGE:
				return f.getName().endsWith(".sqlite");
			case HILLSHADE:
				return f.getName().endsWith(".sqlitedb");
			case FONTS:
				return f.getName().endsWith(".otf.zip");
			case VOICE:
				return f.getName().endsWith(".voice.zip");
				
			}
			return false;
		}
	    
		
		public String getDefaultTitle(String regionName) {
			switch (this) {
			case MAP:
				return String.format("Map, Roads, POI, Transport, Address data for %s", regionName);
			case ROAD_MAP:
				return String.format("Roads, POI, Address data for %s", regionName);
			case WIKI_MAP:
				return String.format("Wikipedia POI data for %s", regionName);
			case DEPTH:
				return String.format("Depth contours for %s", regionName);
			case SRTM_MAP:
				return String.format("Contour lines for %s", regionName);
			case WIKIVOYAGE:
				return String.format("Wikivoyage for %s", regionName);
			case HILLSHADE:
				return String.format("Hillshade for %s", regionName);
			case FONTS:
				return String.format("Fonts %s", regionName);
			case VOICE:
				return String.format("Voice package: %s", regionName);
			}
			return "";
		}
	    
	    
	    public String getType() {
	    	return name().toLowerCase();
	    }


	}

	
}
