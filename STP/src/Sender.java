import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Random;
import java.util.Scanner;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;

import javax.swing.text.PlainDocument;


/*
 * 封装STPpacket数据的类
 * */
class STP_Packet{
	
	public int ACK; // ACK码 0 or 
	public int SYN; // SYN码 0 or 1
	public int FIN; // 0 or 
	public int DATA; //0 or 
	public String data;//需要传输的数据
	public int seq; //序列号
	public int ack; // ack号
	public short length; // 数据长度
	public short mws; //MWS
	public short mss; // MSS
	
	public STP_Packet(int ackType,int synType,int finType,int dataType,int seq,int ack,short mws,short mss,String data,short len) {
		// TODO Auto-generated constructor stub
		
		this.ACK = ackType;
		this.SYN = synType;
		this.FIN = finType;
		this.DATA = dataType;
		this.data = data;
		this.seq =  seq;
		this.ack = ack;
		this.length = len;

		this.mws = mws;
		this.mss = mss;
		
	}
	
	/*
	 * 设置数据
	 * */
	public void setData(String data){
		this.data=  data;
	}

	/*
	 * header数据封装成bytes数据
	 * */
	public byte[] getHeader() throws UnsupportedEncodingException{
		long temp;
		byte type = (byte) (((((((this.ACK << 1) | this.SYN) << 1) | this.FIN )<< 1 )| this.DATA) << 4);
		
		byte[] seq = new byte[4];	
		seq[0] = (byte)((this.seq & 0x7f000000)>> 28);
		seq[1] = (byte)((this.seq & 0x00ff0000)>> 16);
		seq[2] = (byte)((this.seq & 0x0000ff00)>> 8);
		seq[3] = (byte)((this.seq & 0x000000ff));
		
		byte[] ack = new byte[4];
		
		ack[0] = (byte)((this.ack & 0x7f000000)>> 28);
		ack[1] = (byte)((this.ack & 0x00ff0000)>> 16);
		ack[2] = (byte)((this.ack & 0x0000ff00)>> 8);
		ack[3] = (byte)((this.ack & 0x000000ff));
		
		byte[] mws = new byte[2];
		mws[0] = (byte)((this.mws & 0x7f00)>> 8);
		mws[1] = (byte)(this.mws & 0x00ff);
		
		byte[] mss = new byte[2];
		mss[0] = (byte)((this.mss & 0x7f00)>> 8);
		mss[1] = (byte)(this.mss & 0x00ff);

		byte[] length = new byte[2];
		length[0] = (byte)((this.length & 0xff00)>> 8);
		length[1] = (byte)(this.length & 0x00ff);
		
		int len = 1 + seq.length + ack.length + mws.length + mss.length + length.length;
		byte[] header = new byte[len];
		
		header[0] = type;

		for(int i=1;i<seq.length+1;i++){
			header[i] = seq[i-1];
			header[i+4] = ack[i-1];
		}
		
		for(int i=0;i<2;i++){
			header[9+i] = mws[i];
			header[11+i] = mss[i];
			header[13+i] = length[i];
		}

		return header;
		
	}
	
	//获取packet完整的byte数据
	public byte[] getPacketByte() throws UnsupportedEncodingException{
		
		byte[] data = this.data.getBytes();
		byte[] header = this.getHeader();
		
		if(data.length == 0){
			return header;
		}
		int len = data.length+header.length;
		
		byte[] packet = new byte[len];
		for(int i=0;i<header.length;i++){
			packet[i] = header[i];
		}
		
		for(int i=header.length;i<len;i++){
			packet[i] = data[i-header.length];
		}
		
		return packet;
	}
}

/*
 * 计时器
 * */
class Timer{
	int timeout;
	long startTime;
	boolean closed;
	long stopTime;

	public Timer(int timeout){
		this.timeout = timeout;
		this.startTime = new Date().getTime();
		this.closed = false;
	}
	
	public void close(){
		this.closed = true;
	}
	
	public boolean stop(){
		this.stopTime = new Date().getTime();
		if(this.closed == true){
			return false;
		}
		
		if (this.timeout > (this.stopTime-this.startTime)){
			return false;
		}else {
			return true;
		}
	}
}


public class Sender {
	
	boolean stateFin1 ;
	boolean stateFin2;
	boolean established ;
	
	int retransmit;
	long startTime;
	int MWS;
	int MSS;
	int timeout;
	int Seed;
	float pdrop;
	
	int front;
	int back;
	int waitVerify;
	int beginTransmit;
	
	int seq;
	int ack;
	
	ArrayDeque<Integer> tempCache ;
	ArrayDeque<STP_Packet> tempEntryCache ;
	ArrayDeque<Integer> lossList ;
	
	ArrayDeque<Timer> timerList;
	PLD pld ;
	
	DatagramSocket sock ;
	InetAddress ias ;
	String ip ;
	int port;
	
	String filePath ;
	BufferedWriter bw ;
	File log ;
	Random random = new Random();
	
	int dataSegs = 0;
	int sendSegs = 0;
	int dropSegs = 0;
	int delaySegs = 0;
	int retransmitSegs = 0;
	int ReAckSegs = 0;
	
	
	
	public Sender(String ip, int port,int MWS, int MSS, int Seed,float pdrop,int timeout,String filePath) throws IOException{
		
		this.MWS = MWS;
		this.MSS = MSS;
		this.Seed = Seed;
		this.pdrop = pdrop;
		this.timeout = timeout;
		tempCache = new ArrayDeque<Integer>();
		tempEntryCache = new ArrayDeque<STP_Packet>();
		lossList= new ArrayDeque<Integer>();
		timerList  = new ArrayDeque<Timer>();
		pld =  new PLD(this.Seed,this.pdrop);
		
		log = new File("Sender_log.txt","w");
		this.random.setSeed(this.Seed);
		this.sock =  new DatagramSocket();
		this.ip = ip;
		this.port = port;
		
		this.filePath = filePath;
		bw = new BufferedWriter(new FileWriter(new File("Sender_log.txt")));
		
	}
	//获取双端队列指定位置的数据
	public Object get(ArrayDeque list, int index){
		int temp_pos =0;
		for(Object obj:list){
			if(temp_pos == index){
				return obj;
			}
			temp_pos ++ ;
		}
		return null;
		
	}
	
	//检查是否存在超时的timer
	public boolean checkTimeout(){
		int timout_pos = -1;
		int i=0;
		for(Timer timer : this.timerList){
			if(timer.stop() == true){
				timout_pos = i;
				break;
			}
			i++;
		}
		
		/*
		 * 如果有超时，就返回True， 同时超时的packet 在 temp_cache之后的所有packet的timer关闭
		 * */
		
		if (timout_pos == -1){
			return false;
		}else {
			int temp = 0;
			int temp_pos = 0;
			for(Integer t:this.tempCache){
				if(temp_pos == timout_pos){
					this.lossList.addLast(t);
					break;
				}
				temp_pos ++ ;
			}
			
			//超时的计时器后面的所有计时器都关闭
			temp_pos = 0;
			for(Timer timer:this.timerList){
				if(temp_pos>timout_pos){
					timer.close();
					break;
				}
				temp_pos ++ ;
			}
			return true;
		}
	}
	
	//处理超时
	public void handleTimeout(){
		//把loss列表抛出最先超时的packet
		int tempAck = this.lossList.pollFirst();
		this.waitVerify = tempAck;
		this.retransmitSegs +=(this.waitVerify-tempAck);
		//清空超时的packet后的所有packet
		this.clearBackOfUnack(tempAck);
	}
	
	//获取到指定obj在列表中的位置
	int getIndex(ArrayDeque list,Object obj){
		int index = 0;
		for(Object t : list){
			if( t.equals(obj)){
				return index;
			}
			index ++;
		}
		return index;
	}
	
	//情况unack所在位置后的所有packet
	public void clearBackOfUnack(int UnAck){
		
		int index = this.getIndex(this.tempCache, UnAck);
		int tempLen  =this.tempCache.size();
		for(int i=0;i<tempLen;i++){
			if(i >= index){
				this.tempCache.pollLast();
				this.tempEntryCache.pollLast();
				this.timerList.pollLast();
			}
		}
	}
	//将所有confirmAck之前的这些有序packet进行确认，同时更新各列表
	public void confirm(int confirmAck){
		int index = this.getIndex(this.tempCache, (Integer)confirmAck);
		index += 1;
		this.sendSegs += index;
		
		for(int i=0;i<index;i++){
			//确定可靠发送成功
			System.out.println("发送成功");
			this.tempCache.pollFirst();
			this.tempEntryCache.pollFirst();
			this.timerList.pollFirst();
			this.back += this.MSS;
			this.front += this.MSS;
		}
	}
	
	public void close() throws IOException{
		STP_Packet sp = new STP_Packet(0, 0, 1, 0, this.waitVerify-this.MSS, this.seq, (short)this.MWS, (short)this.MSS, "", (short)0);
		
		byte[] data = sp.getPacketByte();
		DatagramPacket dp = new DatagramPacket(data, data.length,InetAddress.getByName("127.0.0.1"),this.port);
		
		this.stateFin1 = true;
		this.established = false;
		
		this.sock.send(dp);
		
		float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
		
		String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"F",(this.waitVerify-this.MSS)+"","0",this.ack);
		this.bw.write(format);
		this.bw.flush();
	}
	
	
	public HashMap<String, String> parseData(byte[] data){
		
		byte type = data[0];
		int ackType = (type  & 0x80) >> 7;
		int synType = (type & 0x40) >> 6;
		int finType = (type & 0x20) >> 5;
		int dataType = (type & 0x10) >> 4;
		
		int seq = (((data[1] & 0x7f) << 28) | (((data[2] << 16) & 0xffffff) |( ((data[3] << 8) & 0xffff) | (data[4] & 0xff) & 0xffff )& 0xffffff ) & 0x7fffffff) ;
		int ack = (((data[5] & 0x7f) << 28)  | (((data[6] << 16) & 0xffffff) |(((data[7] << 8) & 0xffff) | (data[8] & 0xff) & 0xffff ) & 0xffffff )  & 0x7fffffff) ;


		int mws =  (((data[9] << 8) | (data[10] & 0xff )));
		int mss = (data[11] << 8) | (data[12] & 0xff);
		int length =  (((data[13] << 8) | (data[14] & 0xff)));
		
		String dataStr = new String(data,14,data.length-14);
		
		HashMap<String, String> packetData = new HashMap<String, String>();
		packetData.put("ACK", ""+ackType);
		packetData.put("SYN", ""+synType);
		packetData.put("FIN", ""+finType);
		packetData.put("DATA", ""+dataType);
		packetData.put("seq", ""+seq);
		packetData.put("ack", ""+ack);
		packetData.put("MWS", ""+mws);
		packetData.put("MSS", ""+mss);
		packetData.put("data", ""+dataStr);
		packetData.put("length", ""+length);
		
		return packetData;
		
	}
	
	
	
	public boolean isExist(ArrayDeque list,Object obj){
		for(Object temp: list){
			if(temp.equals(obj)){
				return true;
			}
		}
		return false;
		
	}
	
	
	
	
	public void send() throws IOException{
		
		this.startTime = System.nanoTime();
		this.beginTransmit = this.random.nextInt(100);
		
		/*
		 * 第一次握手
		 * */
		STP_Packet packet = new STP_Packet(0, 1, 0, 0, this.beginTransmit, 0, (short)this.MWS, (short)this.MSS, "", (short)0);
		
		byte[] data = packet.getPacketByte();
		DatagramPacket dp = new DatagramPacket(data, data.length,InetAddress.getByName("127.0.0.1"),this.port);
		
		this.sock.send(dp);
		float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
		
		String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"S",beginTransmit + "","0","0");
		this.bw.write(format);
		this.bw.flush();
		
		//this.sock.setSoTimeout();

		/*
		 * 等待返回数据
		 * 
		 * */
		
		byte[] buffer = new byte[1024];
		
		DatagramPacket recv = new DatagramPacket(buffer, 1024);
		
		this.sock.receive(recv);
		
		HashMap<String, String> responseData = this.parseData(recv.getData());
		
		int seq_r = Integer.valueOf(responseData.get("seq"));
		int ack_r= Integer.valueOf(responseData.get("ack"));
		int ackT = Integer.valueOf(responseData.get("ACK"));
		int synT = Integer.valueOf(responseData.get("SYN"));
		int finT = Integer.valueOf(responseData.get("FIN"));
		int dataT = Integer.valueOf(responseData.get("DATA"));
		
		if(ackT != 1 || synT != 1){
			System.err.println("connect error");
			return ;
		}
		
		this.front = ack_r;
		this.waitVerify = this.front;
		this.back = this.front + this.MWS;
		this.beginTransmit = this.front;
		innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
		this.ack = Integer.valueOf(responseData.get("seq")) + 1;
		
		format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"SA",responseData.get("seq"),"0",responseData.get("ack"));
		this.bw.write(format);
		this.bw.flush();
		/*
		 * 第二次握手
		 * */
		
		packet = new STP_Packet(1, 0, 0, 0, ack_r, seq_r+1, (short)this.MWS, (short)this.MSS, "", (short)0);
		
		data = packet.getPacketByte();
		dp = new DatagramPacket(data, data.length,InetAddress.getByName("127.0.0.1"),this.port);
		
		this.sock.send(dp);
		this.seq = this.waitVerify;
		this.ack = seq_r + 1;
		this.established = true;
		
		innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
		format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"S",this.waitVerify+"","0",responseData.get("seq"));
		this.bw.write(format);
		this.bw.flush();
		this.ack = Integer.valueOf(responseData.get("seq")) + 1;
		this.seq = this.waitVerify;
		
		System.out.println("connect correct ");
		
		/*
		 * 以上为止 ，三次握手成功，发送数据开始
		 * 
		 * 
		 * */

		RandomAccessFile randomFile = new RandomAccessFile(this.filePath,"r");

		while(true){
			
			long size = randomFile.length();

			if(this.established == true){
				if(this.back-this.waitVerify > 0){
					long startRead = this.waitVerify-this.beginTransmit;
					byte[] fileData = new byte[this.MSS];
					randomFile.seek(startRead);
					System.out.println((size - this.waitVerify+this.beginTransmit) + "   " +( size - this.waitVerify+this.beginTransmit));
					short tempLen = (short)((size - this.waitVerify+this.beginTransmit) < this.MSS ? (size - this.waitVerify+this.beginTransmit) : this.MSS );
					int len_f = randomFile.read(fileData,  0, this.MSS);
					
					if(len_f == -1 ){
						if(this.waitVerify == this.front){
							/*
							 * 开始四次握手关闭
							 * 
							 * */
							this.close();

						}
					}else {
							packet = new STP_Packet(1, 0, 0, 1, this.waitVerify, this.ack, (short)this.MWS, (short)this.MSS, "", tempLen);
							packet.setData(new String(fileData));
							data = packet.getPacketByte();
							dp = new DatagramPacket(data, data.length,InetAddress.getByName("127.0.0.1"),this.port);
							
							this.tempCache.addLast(this.waitVerify);
							this.tempEntryCache.addLast(packet);
							this.timerList.addLast(new Timer(this.timeout));
							this.dataSegs += tempLen;
							
							if(this.pld.drop() == false){
								this.sock.send(dp);
								/*
								 * log
								 * */
								
								innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
								format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"D",this.waitVerify + "",tempLen+"",this.ack+"");
								this.bw.write(format);
								this.bw.flush();

							}else {
								/*
								 * log pdrop
								 * */
								
								this.dropSegs ++;
								
								innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
								format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","drop",innerTime,"D",this.waitVerify + "",tempLen+"",this.ack+"");
								this.bw.write(format);
								this.bw.flush();
								
							}
							
							this.waitVerify += this.MSS;
						}
					
				}
			}
			
			this.sock.setSoTimeout(1);
			HashMap<String, String> serverData = null ;
			try {
				byte[] buff = new byte[1024];
				DatagramPacket rec = new DatagramPacket(buff, 1024);
				this.sock.receive(rec);
				serverData = this.parseData(rec.getData());
				
			} catch (Exception e) {
				// 无数据
			}

			if(serverData != null){
				this.seq = Integer.valueOf(serverData.get("seq"));
				/*
				 * log 
				 * 
				 * */
				
				int ack_t = Integer.valueOf(serverData.get("ack"));
				
				//System.out.println((ack_t-this.MSS)+ "    " + size + ack_t+"    ------  "+ this.waitVerify + "    " + serverData.get("length") + "   " + this.beginTransmit);

				/*if(ack_t-this.MSS +1 == size){
					System.out.println("========close  ");
					this.close();
				}*/
				
				
				this.ack = Integer.valueOf(serverData.get("seq")) + 1;
				
			}else {
				if(this.checkTimeout() == true){
					/*
					 * log
					 * */

					this.handleTimeout();
				}
				continue;

			}

			ack_r = Integer.valueOf(serverData.get("ack"));
			if(Integer.valueOf(serverData.get("ACK")) == 1 && this.stateFin1 == false){
				int ack_previous = ack_r - this.MSS;
				
				//if(ack_previous+this.beginTransmit)
				
				innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"A",serverData.get("seq") + "",serverData.get("length"),serverData.get("ack"));
				this.bw.write(format);
				this.bw.flush();
				
				if(this.isExist(this.tempCache, (Integer)ack_previous) == true){
					this.confirm(ack_previous);
				}else {
					if(this.isExist(this.tempCache, (Integer)(ack_previous + this.MSS))){
						this.retransmit ++ ;
						this.ReAckSegs ++ ;
					}
				}
				
				/*
				 * 快重传，重确认三次以上则重传
				 * */
				if(this.retransmit >= 3){
					this.retransmit = 0;
					
					this.retransmitSegs += (int)(((this.waitVerify-ack_r-this.MSS))/this.MSS);
					
					this.waitVerify = ack_previous + this.MSS;
					this.clearBackOfUnack(this.front);
					
					
					
					continue;
				}
				
				if(this.checkTimeout() == true){
					this.handleTimeout();
				}

			}else if(Integer.valueOf(serverData.get("ACK")) == 1 && Integer.valueOf(serverData.get("FIN")) == 0 && this.stateFin1 == true ){
				/*
				 * 第二次握手关闭
				 * */
				
				
				innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"FA",serverData.get("seq") + "",serverData.get("length"),serverData.get("ack"));
				this.bw.write(format);
				this.bw.flush();
				
				
				
				this.stateFin2 = true;
				
			}else if(Integer.valueOf(serverData.get("ACK")) == 1 && Integer.valueOf(serverData.get("FIN")) == 1 && this.stateFin2 == true ){
				innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"FA",serverData.get("seq") + "",serverData.get("length"),serverData.get("ack"));
				this.bw.write(format);
				this.bw.flush();
				
				
				
				packet = new STP_Packet(1, 0, 0, 0,Integer.valueOf(serverData.get("ack")), Integer.valueOf(serverData.get("seq"))+1, (short)this.MWS, (short)this.MSS, "", (short)this.MSS);

				data = packet.getPacketByte();
				dp = new DatagramPacket(data, data.length,InetAddress.getByName("127.0.0.1"),this.port);
				this.sock.send(dp);
				
				innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"FA",serverData.get("ack") + "","0",(Integer.valueOf(serverData.get("seq"))+1)+"");
				this.bw.write(format);
				this.bw.flush();
				
				
				format = String.format("%1$20s %2$6d \n","Amount of (original) Data Transferred (in bytes) : ",this.dataSegs);
				this.bw.write(format);
				format = String.format("%1$20s %2$6d \n","Number of Data Segments Sent (excluding retransmissions) : ",this.sendSegs);
				this.bw.write(format);
				format = String.format("%1$20s %2$6d \n","Number of (all) Packets Dropped (by the PLD module) : ",this.dropSegs);
				this.bw.write(format);
				format = String.format("%1$20s %2$6d \n","Number of Retransmitted Segments : ",this.retransmitSegs);
				this.bw.write(format);
				format = String.format("%1$20s %2$6d \n","Number of Duplicate Acknowledgements received : ",this.ReAckSegs);
				this.bw.write(format);
				
				
				
				this.bw.flush();
				
				
				
				this.sock.close();
				System.out.println("关闭socket");
				
				return ;
			}
		}
	}
	

	public static void main(String[] args) throws IOException {
		
		/*System.out.println(Integer.MAX_VALUE);
		
		
		STP_Packet sp = new STP_Packet(1, 1, 1, 1, 33000, 32751, (short)500, (short)50, "", (short)0);
		
		Sender sender = new Sender("127.0.0.1", 8080, 800, 100,300, 0.3f,100,"test2.txt");
		HashMap< String, String> t = sender.parseData(sp.getPacketByte());
		for(Entry<String, String> i : t.entrySet()){
			System.out.println(i.getKey() + "   " + i.getValue());
		}*/
		
		
		
		String ip = args[0];
		int port = Integer.valueOf(args[1]);
		String path = args[2];
		int mws = Integer.valueOf(args[3]);
		int mss = Integer.valueOf(args[4]);
		int timeout = Integer.valueOf(args[5]);
		int seed = Integer.valueOf(args[7]);
		float pdrop = Float.valueOf(args[6]);

		/*
		Sender sender = new Sender("127.0.0.1", 8080, 800, 100,300, 0.3f,100,"test2.txt");
		sender.send();
		 * */

		Sender sender = new Sender(ip, port, mws,mss,seed, pdrop,timeout,path);
		sender.send();
		
	}

}
