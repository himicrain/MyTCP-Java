import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;


class CacheEntry{
	int ack;
	String data;
	public CacheEntry(int ack,String data){
		this.ack = ack;
		this.data = data;
	}
}


public class Receiver {
	
	int port;
	String filePath;
	ArrayDeque<CacheEntry> cache;
	
	int acked;
	int waitForAck ;
	boolean closeWait ;
	boolean lastAck ;
	boolean establish ;
	int retansmitCounter;
	int seq ;
	
	DatagramSocket sock;
	
	short MWS;
	short MSS;
	
	FileWriter fw;
	PrintWriter pw ;
	BufferedWriter bw ;
	
	long startTime;
	BufferedWriter log;
	
	int dataSegs = 0;
	int dataBytes = 0;
	int reAckSeg = 0;
	
	
	public Receiver(int port,String filePath) throws IOException{

		this.port = port;
		this.filePath = filePath;
		
		this.cache = new ArrayDeque<CacheEntry>();
		this.acked = 0;
		this.waitForAck = 0;
		this.closeWait = false;
		this.lastAck = false;
		this.establish = false;
		this.retansmitCounter = 0;
		
		this.seq = 0;
		this.sock = new DatagramSocket(this.port);
		this.fw = new FileWriter(new File(filePath));
		this.pw = new PrintWriter(new File("Receiver_data.txt"));
		this.bw = new BufferedWriter(fw);
		log = new BufferedWriter(new FileWriter(new File("Receive_log.txt")));
		
	}
	

	public HashMap<String, String> parseData(byte[] data) throws UnsupportedEncodingException{
		
		byte type = data[0];
		
		int ackType = (type  & 0x80) >> 7;
		int synType = (type & 0x40) >> 6;
		int finType = (type & 0x20) >> 5;
		int dataType = (type & 0x10) >> 4;
		
		//int seq = (data[1] << 28) | (data[2] << 16) | (data[3] << 8) | (data[4] & 0xff);
		//int ack = (data[5] << 28) | (data[6] << 16) | (data[7] << 8) | (data[8] & 0xff);
		int seq = (((data[1] & 0x7f) << 28) | (((data[2] << 16) & 0xffffff) |( ((data[3] << 8) & 0xffff) | (data[4] & 0xff) & 0xffff )& 0xffffff ) & 0x7fffffff) ;
		int ack = (((data[5] & 0x7f) << 28)  | (((data[6] << 16) & 0xffffff) |(((data[7] << 8) & 0xffff) | (data[8] & 0xff) & 0xffff ) & 0xffffff )  & 0x7fffffff) ;


		int mws =  (((data[9] << 8) | (data[10] & 0xff )));
		int mss = (data[11] << 8) | (data[12] & 0xff);
		int length =  (((data[13] << 8) | (data[14] & 0xff)));
		
		String dataStr = new String(data, 15, length);

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
	
	
	public void connect() throws IOException{

		
		int flag = 0;
		DatagramPacket recv;
		
		int counterTime = 0;
		
		while(true){
			
			byte[] buffer = new byte[1024];
			recv = new DatagramPacket(buffer, 1024);
			this.sock.receive(recv);
			HashMap<String, String> requestData = this.parseData(recv.getData());
			
			if(counterTime == 0){
				this.startTime = System.nanoTime();
				counterTime = 1;
			}
			
			
			this.MWS = Short.valueOf(requestData.get("MWS"));
			this.MSS = Short.valueOf(requestData.get("MSS"));

			if(Integer.valueOf(requestData.get("SYN")) == 1 && Integer.valueOf(requestData.get("ACK")) == 0){
				STP_Packet packet = new STP_Packet(1, 1, 0, 0, this.seq, Integer.valueOf(requestData.get("seq"))+1, (short)this.MWS, (short)this.MSS, "", (short)0);
				
				byte[] data = packet.getPacketByte();
				DatagramPacket dp = new DatagramPacket(data, data.length,recv.getAddress(),recv.getPort());
				this.sock.send(dp);
				
				float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"SA",requestData.get("seq"),"0",requestData.get("ack"));
				this.log.write(format);
				this.log.flush();

				this.seq ++ ;
				this.waitForAck = Integer.valueOf(requestData.get("seq")) +1;
				flag = 1;
				
			}else if(Integer.valueOf(requestData.get("ACK")) == 1 && Integer.valueOf(requestData.get("SYN")) == 0 && flag == 1){
				
				float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"SA",requestData.get("seq"),"0",requestData.get("ack"));
				this.log.write(format);
				this.log.flush();
				
				this.establish = true;
				return;
			}else {
				continue;
			}
		}
	}
	
	

	public void recv() throws IOException{
		
		for(int i=0;i<this.MWS;i++){
			this.cache.addLast(new CacheEntry(-1, ""));
		}
		
		DatagramPacket rec;
		
		while(true){
			byte[] buffer = new byte[1024];
			rec  = new DatagramPacket(buffer, 1024);
			HashMap<String, String> requestData = null;
			try {
				this.sock.receive(rec);
				
			} catch (Exception e) {
				System.err.println("recv error ");
				return;
			}
			
			requestData = this.parseData(rec.getData());
			
			int seq_r = Integer.valueOf(requestData.get("seq"));
			 
			if(Integer.valueOf(requestData.get("ACK"))==1 && Integer.valueOf(requestData.get("DATA")) == 1 && this.establish == true){
				 
					float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
					String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"D",requestData.get("seq"),requestData.get("length"),requestData.get("ack"));
					this.log.write(format);
					this.log.flush();
					
					this.dataSegs ++ ;
					if(this.cache.contains(seq_r) == false){
						this.dataBytes += Integer.valueOf(requestData.get("length"));
					}
					else {
						this.reAckSeg ++ ;
					}
					
				 if(seq_r < this.waitForAck){
					 continue;
				 }
				 
				 if(seq_r != this.waitForAck){
					 int flag = 0;
					 
					 for(CacheEntry t : this.cache){
						 CacheEntry temp = t;
						 if((Integer)temp.ack == seq_r){
							 flag = 1;
							 break;
						 }
					 }
					 
					 if(flag == 1){
						 continue;
					 } 
				 }
				 
				 if(seq_r == this.waitForAck){
					 int index = (int)((seq_r-this.waitForAck)/this.MSS);
					 this.cache.pollFirst();
					 this.cache.addFirst(new CacheEntry(seq_r, requestData.get("data")));
					 
					 int num = 0;
					 for(CacheEntry t : this.cache){
						 CacheEntry temp = t;
						 if(temp.ack == -1){
							 break;
						 }else {
							num ++ ;
						}
					 }

					 int ack_response = seq_r;
					 
					 for(int i=0;i<num;i++){
						 CacheEntry entry =  this.cache.pollFirst();
						 this.cache.addLast(new CacheEntry(-1, ""));

						 this.waitForAck += this.MSS;
						 ack_response += this.MSS;
						 this.seq += 1;
						 this.retansmitCounter = 0;
						 
						 STP_Packet packet = new STP_Packet(1, 0, 0, 0, this.seq, ack_response, (short)this.MWS, (short)this.MSS, "", (short)(int)(Integer.valueOf(requestData.get("length"))));
							
						 byte[] data = packet.getPacketByte();
						 DatagramPacket dp = new DatagramPacket(data, data.length,rec.getAddress(),rec.getPort());
							
						 this.sock.send(dp);	
						 this.bw.write(entry.data);
						 this.bw.flush();
						 
						 innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
						 format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"A",this.waitForAck,requestData.get("length"),ack_response);
						 this.log.write(format);
						 this.log.flush();
						 
						 this.seq += 1; 
					 }
 
				 }else {
					int index = (int)((seq_r - this.waitForAck)/this.MSS);
					
					
					Object[] temp =  this.cache.toArray();
					
					temp[index] = (Object)new CacheEntry(seq_r, requestData.get("data"));
					
					this.cache.clear();
					
					for(int i=0;i<temp.length;i++){
						this.cache.addLast((CacheEntry)temp[i]);
					}
					
					int ack_response = this.waitForAck;
					
					this.retansmitCounter ++ ;
					
					//if(this.retansmitCounter <=3){
						STP_Packet packet = new STP_Packet(1, 0, 0, 0, this.seq, ack_response, (short)this.MWS, (short)this.MSS, "", (short)0);
						
						 byte[] data = packet.getPacketByte();
						 DatagramPacket dp = new DatagramPacket(data, data.length,rec.getAddress(),rec.getPort());
							
						 this.sock.send(dp);
						 
						 innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
						 format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"A",this.waitForAck,"0",ack_response);
						 this.log.write(format);
						 this.log.flush();
						 this.seq ++ ;
					//}			
				} 
			 }else if (Integer.valueOf(requestData.get("FIN"))==1) {
				 
				 
				 float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"F",requestData.get("seq"),requestData.get("length"),requestData.get("ack"));
				this.log.write(format);
				this.log.flush();
				 
				 
				 
				 STP_Packet packet = new STP_Packet(1, 0, 0, 0, this.seq, Integer.valueOf(requestData.get("seq")), (short)this.MWS, (short)this.MSS, "", (short)0);
				 this.seq ++ ;
				 byte[] data = packet.getPacketByte();
				 DatagramPacket dp = new DatagramPacket(data, data.length,rec.getAddress(),rec.getPort());
					
				 this.sock.send(dp);
				 
				 innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				 format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"F",requestData.get("seq"),"0",requestData.get("ack"));
				 this.log.write(format);
				 this.log.flush();
				 
				 this.closeWait = true;
				 this.establish = true;
				 this.seq ++ ;

				 /*
				  * 
				  * 仍然可以接受数据
				  * 
				  * */
				 
				 packet = new STP_Packet(1, 0, 1, 0, this.seq, Integer.valueOf(requestData.get("seq")), (short)this.MWS, (short)this.MSS, "", (short)0);
				 data = packet.getPacketByte();
				 dp = new DatagramPacket(data, data.length,rec.getAddress(),rec.getPort());
				 this.sock.send(dp);
				 
				 innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				 format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","snd",innerTime,"FA",this.seq,"0",requestData.get("seq"));
				 this.log.write(format);
				 this.log.flush();

			}else if (Integer.valueOf(requestData.get("ACK")) == 1 && Integer.valueOf(requestData.get("DATA"))==0 && this.closeWait == true) {
				
				/*
				 * log
				 * */
				
				float innerTime = (float)((System.nanoTime() - this.startTime)/1000000.0); 
				String format = String.format("%1$6s %2$8.3f %3$6s %4$6s %5$4s %6$6s\n","rcv",innerTime,"A",requestData.get("seq"),"0",requestData.get("ack"));
				this.log.write(format);
				this.log.flush();
				
				
				format = String.format("%1$20s %2$6d \n","Amount of (original) Data Received (in bytes) : ",this.dataBytes);
				this.log.write(format);
				format = String.format("%1$20s %2$6d \n","Number of (original) Data Segments Received : ",this.dataSegs);
				this.log.write(format);
				format = String.format("%1$20s %2$6d \n","Number of duplicate segments received (if any) : ",this.reAckSeg);
				this.log.write(format);
				
				this.log.flush();
				this.lastAck = true;
				this.sock.close();
				
				return ;
				
			}
		}
	}
	
	

	public static void main(String[] args) throws IOException {
		
		int port = Integer.valueOf(args[0]);
		String path = args[1];
		
		Receiver receiver = new Receiver(port, path);
		receiver.connect();
		receiver.recv();

		/*
		Receiver receiver = new Receiver("127.0.0.1", 8080, "file.txt");
		receiver.connect();
		receiver.recv();
		*/

	}

}
