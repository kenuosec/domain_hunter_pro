package title;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.alibaba.fastjson.JSON;
import com.google.common.hash.HashCode;
import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import burp.BurpExtender;
import burp.Getter;
import burp.IBurpExtenderCallbacks;
import burp.IExtensionHelpers;
import burp.IHttpRequestResponse;
import burp.IHttpService;
import burp.IRequestInfo;
import burp.IResponseInfo;

public class LineEntry {
	private static final Logger log=LogManager.getLogger(LineEntry.class);
	public static final String Level_A = "重要";
	public static final String Level_B = "无用";
	public static final String Level_C = "一般";

	public static final String[] LevelArray = {LineEntry.Level_A, LineEntry.Level_B, LineEntry.Level_C};

	public static final String CheckStatus_UnChecked = "UnChecked";
	public static final String CheckStatus_Checked = "Done";
	public static final String CheckStatus_Checking = "Checking";
	
	public static final String[] CheckStatusArray = {LineEntry.CheckStatus_UnChecked, LineEntry.CheckStatus_Checking,LineEntry.CheckStatus_Checked};

	//	public static final String Tag_Manager = "管理端";
	//	public static final String Tag_UserEnd = "用户端";
	//	public static final String Tag_TestEnvironment = "测试环境";
	//	public static final String Tag_Useless = "无用";
	//	public static final String Tag_MoreDig = "需复测"; //需要定期review的网站

	public static String systemCharSet = getSystemCharSet();

	private int port =-1;
	private String host = "";
	private String protocol ="";
	//these three == IHttpService, helpers.buildHttpService to build. 

	private byte[] request = {};
	private byte[] response = {};
	// request+response+httpService == IHttpRequestResponse,burp的划分方式

	//used in UI,the fields to show,平常的划分方式
	private String url = "";
	private int statuscode = -1;
	private int contentLength = -1;
	private String title = "";
	private String IP = "";
	private String CDN = "";
	private String webcontainer = "";
	private String time = "";

	//Gson中，加了transient表示不序列号，是最简单的方法
	//给不想被序列化的属性增加transient属性---java特性
	//private transient String messageText = "";//use to search
	//private transient String bodyText = "";//use to adjust the response changed or not
	//don't store these two field to reduce config file size.

	//field for user
	private transient boolean isChecked =false;
	private String CheckStatus =CheckStatus_UnChecked;
	private String Level = Level_C;
	private String comment ="";
	private boolean isManualSaved = false;

	private transient IHttpRequestResponse messageinfo;

	//remove IHttpRequestResponse field ,replace with request+response+httpService(host port protocol). for convert to json.

	private transient IExtensionHelpers helpers;
	private transient IBurpExtenderCallbacks callbacks;

	LineEntry(){

	}

	public LineEntry(String host,Set<String> IPset) {
		this.host = host;
		this.port = 80;
		this.protocol ="http";

		if (this.IP != null) {
			this.IP = IPset.toString().replace("[", "").replace("]", "");
		}
	}

	public LineEntry(IHttpRequestResponse messageinfo) {
		this.messageinfo = messageinfo;
		this.callbacks = BurpExtender.getCallbacks();
		this.helpers = this.callbacks.getHelpers();
		parse();
	}

	public LineEntry(IHttpRequestResponse messageinfo,String CheckStatus,String comment) {
		this.messageinfo = messageinfo;
		this.callbacks = BurpExtender.getCallbacks();
		this.helpers = this.callbacks.getHelpers();
		parse();

		this.CheckStatus = CheckStatus;
		this.comment = comment;
	}

	@Deprecated
	private LineEntry(IHttpRequestResponse messageinfo,boolean isNew,String CheckStatus,String comment,Set<String> IPset,Set<String> CDNset) {
		this.messageinfo = messageinfo;
		this.callbacks = BurpExtender.getCallbacks();
		this.helpers = this.callbacks.getHelpers();
		parse();

		this.CheckStatus = CheckStatus;
		this.comment = comment;
		if (this.IP != null) {
			this.IP = IPset.toString().replace("[", "").replace("]", "");
		}

		if (this.CDN != null) {
			this.CDN = CDNset.toString().replace("[", "").replace("]", "");
		}
	}

	public String ToJson(){//注意函数名称，如果是get set开头，会被认为是Getter和Setter函数，会在序列化过程中被调用。
		return JSON.toJSONString(this);
	}

	public static LineEntry FromJson(String json){//注意函数名称，如果是get set开头，会被认为是Getter和Setter函数，会在序列化过程中被调用。
		return JSON.parseObject(json, LineEntry.class);
	}

	private void parse() {
		try {

			//time = Commons.getNowTimeString();//这是动态的，会跟随系统时间自动变化,why?--是因为之前LineTableModel的getValueAt函数每次都主动调用了该函数。

			IHttpService service = this.messageinfo.getHttpService();

			//url = service.toString();
			url = helpers.analyzeRequest(messageinfo).getUrl().toString();//包含了默认端口
			port = service.getPort();
			host = service.getHost();
			protocol = service.getProtocol();

			if (messageinfo.getRequest() != null){
				request = messageinfo.getRequest();
			}

			if (messageinfo.getResponse() != null){
				response = messageinfo.getResponse();
				IResponseInfo responseInfo = helpers.analyzeResponse(response);
				statuscode = responseInfo.getStatusCode();

				//				MIMEtype = responseInfo.getStatedMimeType();
				//				if(MIMEtype == null) {
				//					MIMEtype = responseInfo.getInferredMimeType();
				//				}


				Getter getter = new Getter(helpers);

				webcontainer = getter.getHeaderValueOf(false, messageinfo, "Server");
				byte[] byteBody = getter.getBody(false, messageinfo);
				try{
					contentLength = Integer.parseInt(getter.getHeaderValueOf(false, messageinfo, "Content-Length").trim());
				}catch (Exception e){
					if (contentLength==-1 && byteBody!=null) {
						contentLength = byteBody.length;
					}
				}

				title = fetchTitle(response);

			}
		}catch(Exception e) {
			e.printStackTrace(BurpExtender.getStderr());
			log.error(e);
		}
	}

	public void DoDirBrute() {

	}

	public String getUrl() {//为了格式统一，和查找匹配更精确，都包含了默认端口
		if (url == null || url.equals("")) {
			return protocol+"://"+host+":"+port+"/";
		}
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public int getStatuscode() {
		return statuscode;
	}

	public void setStatuscode(int statuscode) {
		this.statuscode = statuscode;
	}

	public int getContentLength() {
		return contentLength;
	}

	public void setContentLength(int contentLength) {
		this.contentLength = contentLength;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	//IPString 222.79.64.33, 124.225.183.63
	public String getIP() {
		return IP;
	}
	
	//return IP 的集合
	public Set<String> fetchIPSet() {
		Set<String> result = new HashSet<String>();
		for (String ip: IP.split(",")) {
			result.add(ip.trim());
		}
		return result;
	}

	public void setIP(String iP) {
		IP = iP;
	}

	public void setIPWithSet(Set<String> ipSet) {
		IP = ipSet.toString().replace("[", "").replace("]", "");
	}

	public String getCDN() {
		return CDN;
	}

	public void setCDN(String cDN) {
		CDN = cDN;
	}

	public void setCDNWithSet(Set<String> cDNSet) {
		CDN = cDNSet.toString().replace("[", "").replace("]", "");
	}
	public String getWebcontainer() {
		return webcontainer;
	}

	public void setWebcontainer(String webcontainer) {
		this.webcontainer = webcontainer;
	}

	public String getTime() {
		return time;
	}

	public void setTime(String time) {
		this.time = time;
	}


	public IHttpRequestResponse getMessageinfo() {
		//		if (messageinfo == null){
		//			try{
		//				messageinfo = callbacks.getHelpers().buildHttpMessage()
		//				IHttpRequestResponse messageinfo = new IHttpRequestResponse();
		//				messageinfo.setRequest(this.request);//始终为空，why??? because messageinfo is null ,no object to set content.
		//				messageinfo.setRequest(this.response);
		//				IHttpService service = callbacks.getHelpers().buildHttpService(this.host,this.port,this.protocol);
		//				messageinfo.setHttpService(service);
		//			}catch (Exception e){
		//				System.out.println("error "+url);
		//			}
		//		}
		return messageinfo;
	}

	public void setMessageinfo(IHttpRequestResponse messageinfo) {
		this.messageinfo = messageinfo;
	}

	public String getBodyText() {
		Getter getter = new Getter(BurpExtender.getCallbacks().getHelpers());
		byte[] byte_body = getter.getBody(false, response);
		return new String(byte_body);
	}


	/*
Content-Type: text/html;charset=UTF-8

<meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
<meta charset="utf-8">
<script type="text/javascript" charset="utf-8" src="./resources/jrf-resource/js/jrf.min.js"></script>
	 */

	public static String getCharset(boolean isRequest,byte[] requestOrResponse){
		IExtensionHelpers helpers = BurpExtender.getCallbacks().getHelpers();
		Getter getter = new Getter(helpers);
		String contentType = getter.getHeaderValueOf(isRequest,requestOrResponse,"Content-Type");
		String tmpcharSet = "ISO-8859-1";//http post的默认编码
		
		if (contentType != null){//1、尝试从contentTpye中获取
			if (contentType.toLowerCase().contains("charset=")) {
				tmpcharSet = contentType.toLowerCase().split("charset=")[1];
			}
		}

		if (tmpcharSet == null){//2、尝试使用ICU4J进行编码的检测
			CharsetDetector detector = new CharsetDetector();
			detector.setText(requestOrResponse);
			CharsetMatch cm = detector.detect();
			tmpcharSet = cm.getName();
		}

		tmpcharSet = tmpcharSet.toLowerCase().trim();
		//常见的编码格式有ASCII、ANSI、GBK、GB2312、UTF-8、GB18030和UNICODE等。
		List<String> commonCharSet = Arrays.asList("ASCII,ANSI,GBK,GB2312,UTF-8,GB18030,UNICODE,utf8".toLowerCase().split(","));
		for (String item:commonCharSet) {
			if (tmpcharSet.contains(item)) {
				tmpcharSet = item;
			}
		}
		
		if (tmpcharSet.equals("utf8")) tmpcharSet = "utf-8";
		return tmpcharSet;
	}

	//https://javarevisited.blogspot.com/2012/01/get-set-default-character-encoding.html
	private static String getSystemCharSet() {
		return Charset.defaultCharset().toString();

		//System.out.println(System.getProperty("file.encoding"));
	}


	public String covertCharSet(byte[] response) {
		String originalCharSet = getCharset(false,response);
		//BurpExtender.getStderr().println(url+"---"+originalCharSet);

		if (originalCharSet != null && !originalCharSet.equalsIgnoreCase(systemCharSet)) {
			try {
				System.out.println("正将编码从"+originalCharSet+"转换为"+systemCharSet+"[windows系统编码]");
				byte[] newResponse = new String(response,originalCharSet).getBytes(systemCharSet);
				return new String(newResponse,systemCharSet);
			} catch (Exception e) {
				e.printStackTrace(BurpExtender.getStderr());
				log.error(e);
				BurpExtender.getStderr().print("title 编码转换失败");
			}
		}
		return new String(response);
	}

	public String fetchTitle(byte[] response) {
		String bodyText = covertCharSet(response);

		Pattern p = Pattern.compile("<title(.*?)</title>");
		//<title ng-bind="service.title">The Evolution of the Producer-Consumer Problem in Java - DZone Java</title>
		Matcher m  = p.matcher(bodyText);
		while ( m.find() ) {
			title = m.group(0);
		}
		if (title.equals("")) {
			Pattern ph = Pattern.compile("<title [.*?]>(.*?)</title>");
			Matcher mh  = ph.matcher(bodyText);
			while ( mh.find() ) {
				title = mh.group(0);
			}
		}
		if (title.equals("")) {
			Pattern ph = Pattern.compile("<h[1-6]>(.*?)</h[1-6]>");
			Matcher mh  = ph.matcher(bodyText);
			while ( mh.find() ) {
				title = mh.group(0);
			}
		}
		title = title.replaceAll("<.*?>", "");
		
		if (statuscode == 302 || statuscode == 301) {
			String Locationurl = getHeaderValueOf(false,"Location");
			if (null != Locationurl) {
				title  = title +" --> "+Locationurl;
			}
		}
		return title;
	}

	public String getHeaderValueOf(boolean messageIsRequest,String headerName) {
		helpers = BurpExtender.getCallbacks().getHelpers();
		List<String> headers=null;
		if(messageIsRequest) {
			if (this.request == null) {
				return null;
			}
			IRequestInfo analyzeRequest = helpers.analyzeRequest(this.request);
			headers = analyzeRequest.getHeaders();
		}else {
			if (this.response == null) {
				return null;
			}
			IResponseInfo analyzeResponse = helpers.analyzeResponse(this.response);
			headers = analyzeResponse.getHeaders();
		}


		headerName = headerName.toLowerCase().replace(":", "");
		String Header_Spliter = ": ";
		for (String header : headers) {
			if (header.toLowerCase().startsWith(headerName)) {
				return header.split(Header_Spliter, 2)[1];//分成2部分，Location: https://www.jd.com
			}
		}
		return null;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}

	public byte[] getRequest() {
		return request;
	}

	public void setRequest(byte[] request) {
		this.request = request;
	}

	public byte[] getResponse() {
		return response;
	}

	public void setResponse(byte[] response) {
		this.response = response;
	}

	//程序中不再需要使用 isChecked函数（Checked属性的getter），完全移除
	@Deprecated//在反序列化时，还会需要这个函数，唯一的使用点。
	public void setChecked(boolean isChecked) {
		//如果是旧数据，将这个值设置到新的属性，为了向下兼容需要保留这个函数。
		try {
			if (isChecked) {
				CheckStatus = CheckStatus_Checked;
			}else {
				CheckStatus = CheckStatus_UnChecked;
			}
			//DBHelper dbHelper = new DBHelper(GUI.currentDBFile.toString());
			//dbHelper.updateTitle(this);
			//不能在这里就进行写入，可能对象的属性都还没设置全呢，会导致数据丢失
		} catch (Exception e) {

		}
		this.isChecked = isChecked;
	}

	public String getCheckStatus() {
		return CheckStatus;
	}

	//反序列化时，如果没有这个属性是不会调用这个函数的。
	public void setCheckStatus(String checkStatus) {
		CheckStatus = checkStatus;
	}

	public String getLevel() {
		return Level;
	}

	public void setLevel(String level) {
		if (level.equalsIgnoreCase(Level_A)
				||level.equalsIgnoreCase(Level_B)
				||level.equalsIgnoreCase(Level_C)) {
			Level = level;
		}
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public boolean isManualSaved() {
		return isManualSaved;
	}

	public void setManualSaved(boolean isManualSaved) {
		this.isManualSaved = isManualSaved;
	}

	public IExtensionHelpers getHelpers() {
		return helpers;
	}

	public void setHelpers(IExtensionHelpers helpers) {
		this.helpers = helpers;
	}

	public Object getValue(int columnIndex) {
		// TODO Auto-generated method stub
		return null;
	}

	public static void main(String args[]) {
		//		LineEntry x = new LineEntry();
		//		x.setRequest("xxxxxx".getBytes());
		//		//		System.out.println(yy);
		//		System.out.println(getSystemCharSet());
		//		System.out.println(System.getProperty("file.encoding"));
		//		System.out.println(Charset.defaultCharset());

		String item = "{\"bodyText\":\"<!DOCTYPE html PUBLIC \\\"-//W3C//DTD XHTML 1.0 Strict//EN\\\" \\\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\\\">\\r\\n<html xmlns=\\\"http://www.w3.org/1999/xhtml\\\">\\r\\n<head>\\r\\n<meta http-equiv=\\\"Content-Type\\\" content=\\\"text/html; charset=iso-8859-1\\\" />\\r\\n<title>IIS Windows Server</title>\\r\\n<style type=\\\"text/css\\\">\\r\\n<!--\\r\\nbody {\\r\\n\\tcolor:#000000;\\r\\n\\tbackground-color:#0072C6;\\r\\n\\tmargin:0;\\r\\n}\\r\\n\\r\\n#container {\\r\\n\\tmargin-left:auto;\\r\\n\\tmargin-right:auto;\\r\\n\\ttext-align:center;\\r\\n\\t}\\r\\n\\r\\na img {\\r\\n\\tborder:none;\\r\\n}\\r\\n\\r\\n-->\\r\\n</style>\\r\\n</head>\\r\\n<body>\\r\\n<div id=\\\"container\\\">\\r\\n<a href=\\\"http://go.microsoft.com/fwlink/?linkid=66138&amp;clcid=0x409\\\"><img src=\\\"iis-85.png\\\" alt=\\\"IIS\\\" width=\\\"960\\\" height=\\\"600\\\" /></a>\\r\\n</div>\\r\\n</body>\\r\\n</html>\",\"cDN\":\"\",\"checkStatus\":\"Checked\",\"comment\":\"\",\"contentLength\":701,\"host\":\"193.112.174.9\",\"iP\":\"193.112.174.9\",\"level\":\"一般\",\"port\":80,\"protocol\":\"http\",\"request\":\"R0VUIC8gSFRUUC8xLjENCkhvc3Q6IDE5My4xMTIuMTc0LjkNCkFjY2VwdC1FbmNvZGluZzogZ3ppcCwgZGVmbGF0ZQ0KQWNjZXB0OiAqLyoNCkFjY2VwdC1MYW5ndWFnZTogZW4NClVzZXItQWdlbnQ6IE1vemlsbGEvNS4wIChXaW5kb3dzIE5UIDEwLjA7IFdpbjY0OyB4NjQpIEFwcGxlV2ViS2l0LzUzNy4zNiAoS0hUTUwsIGxpa2UgR2Vja28pIENocm9tZS84MC4wLjM5ODcuMTMyIFNhZmFyaS81MzcuMzYNCkNvbm5lY3Rpb246IGNsb3NlDQoNCg==\",\"response\":\"SFRUUC8xLjEgMjAwIE9LDQpDb250ZW50LVR5cGU6IHRleHQvaHRtbA0KTGFzdC1Nb2RpZmllZDogVGh1LCAyOCBGZWIgMjAxOSAwOTozMzoyNyBHTVQNCkFjY2VwdC1SYW5nZXM6IGJ5dGVzDQpFVGFnOiAiYTg3ZGI4YTg0OGNmZDQxOjAiDQpWYXJ5OiBBY2NlcHQtRW5jb2RpbmcNClNlcnZlcjogTWljcm9zb2Z0LUlJUy84LjUNClgtUG93ZXJlZC1CeTogQVNQLk5FVA0KRGF0ZTogV2VkLCAxMyBNYXkgMjAyMCAxMDoyNDowNyBHTVQNCkNvbm5lY3Rpb246IGNsb3NlDQpDb250ZW50LUxlbmd0aDogNzAxDQoNCjwhRE9DVFlQRSBodG1sIFBVQkxJQyAiLS8vVzNDLy9EVEQgWEhUTUwgMS4wIFN0cmljdC8vRU4iICJodHRwOi8vd3d3LnczLm9yZy9UUi94aHRtbDEvRFREL3hodG1sMS1zdHJpY3QuZHRkIj4NCjxodG1sIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8xOTk5L3hodG1sIj4NCjxoZWFkPg0KPG1ldGEgaHR0cC1lcXVpdj0iQ29udGVudC1UeXBlIiBjb250ZW50PSJ0ZXh0L2h0bWw7IGNoYXJzZXQ9aXNvLTg4NTktMSIgLz4NCjx0aXRsZT5JSVMgV2luZG93cyBTZXJ2ZXI8L3RpdGxlPg0KPHN0eWxlIHR5cGU9InRleHQvY3NzIj4NCjwhLS0NCmJvZHkgew0KCWNvbG9yOiMwMDAwMDA7DQoJYmFja2dyb3VuZC1jb2xvcjojMDA3MkM2Ow0KCW1hcmdpbjowOw0KfQ0KDQojY29udGFpbmVyIHsNCgltYXJnaW4tbGVmdDphdXRvOw0KCW1hcmdpbi1yaWdodDphdXRvOw0KCXRleHQtYWxpZ246Y2VudGVyOw0KCX0NCg0KYSBpbWcgew0KCWJvcmRlcjpub25lOw0KfQ0KDQotLT4NCjwvc3R5bGU+DQo8L2hlYWQ+DQo8Ym9keT4NCjxkaXYgaWQ9ImNvbnRhaW5lciI+DQo8YSBocmVmPSJodHRwOi8vZ28ubWljcm9zb2Z0LmNvbS9md2xpbmsvP2xpbmtpZD02NjEzOCZhbXA7Y2xjaWQ9MHg0MDkiPjxpbWcgc3JjPSJpaXMtODUucG5nIiBhbHQ9IklJUyIgd2lkdGg9Ijk2MCIgaGVpZ2h0PSI2MDAiIC8+PC9hPg0KPC9kaXY+DQo8L2JvZHk+DQo8L2h0bWw+\",\"statuscode\":200,\"time\":\"2020-05-22-11-07-45\",\"title\":\"<title>IIS Windows Server</title>\",\"url\":\"http://193.112.174.9:80/\",\"webcontainer\":\"Microsoft-IIS/8.5\"}";
		LineEntry entry = LineEntry.FromJson(item);
		System.out.println(entry.getCheckStatus());
		System.out.println(entry.getLevel());
		System.out.println(entry.getTime());
		String key = HashCode.fromBytes(entry.getRequest()).toString();
		System.out.println(key);
	}
}
