/**
 * @author Caitlin Ross and Erika Mackin
 *
 * Node object
 */
import java.util.*;
import java.net.*;
import java.io.*;

public class Node {
	private int port;
	private ArrayList<String> hostNames;
	private int nodeId;
	private int numNodes; 
	private String logName;
	private String stateLog;
	private int leaderId;
	private int incAmt; // amount to increment m (proposal numbers) by to have unique numbers
	
	// Paxos vars
	private int maxPrepare;
	private int accNum;
	private LogEntry accVal;
	private int m;
	private ArrayList<LogEntry> log; // store log entries in order
	private int logPos; // which log position to work on
	private LogEntry newEntry; // entry to try to add 
	
	// keeping track of promise and ack responses
	private LogEntry[] responseVals;
	private int[] responseNums;
	private LogEntry[] ackRespVals;
	private int[] ackRespNums;

	// variables that need to be concerned with synchronization
	private Object lock = new Object();
	private int calendars[][][]; //stores a 0 or 1 for each time period to represent appointment or free
	private Set<Appointment> currentAppts;

	/**
	 * @param totalNodes number of nodes being used
	 * @param port port to use for connections
	 * @param hostNames hostnames for all nodes
	 * @param nodeID this nodes id number
	 * @param recovery is this a recovery startup?	
	 */
	public Node(int totalNodes, int port, String[] hostNames, int nodeID, boolean recovery) {
		this.log = new ArrayList<LogEntry>();
		this.logName = "appointments.log";
		this.stateLog = "nodestate.txt";
		this.nodeId = nodeID;
		this.numNodes = totalNodes;
		this.port = port;
		this.hostNames = new ArrayList<String>();
		for (int i = 0; i<hostNames.length; i++){
			this.hostNames.add(hostNames[i]);
		}
		this.maxPrepare = 0;
		this.logPos = 0;
		this.m = nodeID;  
		this.incAmt = totalNodes;
		this.newEntry = null;
		
		this.calendars = new int[totalNodes][7][48];
		this.currentAppts = new HashSet<Appointment>();  // keep appointments from most recent log entry
		
		this.responseVals = new LogEntry[this.numNodes];
		this.responseNums = new int[this.numNodes];
		for (int i = 0; i < this.responseNums.length; i++){
			this.responseNums[i] = -1;
		}
		this.ackRespVals = new LogEntry[this.numNodes];
		this.ackRespNums = new int[this.numNodes];
		for (int i = 0; i < this.ackRespNums.length; i++){
			this.ackRespNums[i] = -1;
		}
		
		// recover node state if this is restarting from crash
		if (recovery)
			restoreNodeState();
		
		// TODO remove this once leader election is added; just used to test Paxos without leader election and crashes
		this.leaderId = 0;
		
	}

	/**
	 * @return the nodeId
	 */
	public int getNodeId() {
		return nodeId;
	}
	
	/**
	 * @return the nodeId
	 */
	public int[][][] getCalendars() {
		return calendars;
	}
	
	/** TODO needs to send info to distinguished proposer
	 * 
	 * @param nodes participants in the new appointment
	 * @param name name of appointment
	 * @param day day of appointment
	 * @param start start time of appointment
	 * @param end end time of appointment
	 * @param sAMPM am or pm for start time?
	 * @param eAMPM am or pm for end time?
	 */
	public void createNewAppointment(ArrayList<Integer> nodes, String name, Day day, int start, int end, String sAMPM, String eAMPM){
		Appointment newAppt = null;
		int startIndex = Appointment.convertTime(start, sAMPM);
		int endIndex = Appointment.convertTime(end, eAMPM);
		
		// check calendar
		boolean timeAvail = true;
		int time = startIndex;
		while(timeAvail && time < endIndex){
			for (Integer node:nodes){
				synchronized(lock){
					if (this.calendars[node][day.ordinal()][time] != 0){
						timeAvail = false;
					}
				}
			}
			time++;
		}
		
		// create appointment object
		if (timeAvail){
			time = startIndex;
			while(time < endIndex){
				for(Integer node:nodes){
					synchronized(lock){
						this.calendars[node][day.ordinal()][time] = 1;
					}
				}
				time++;
			}
			newAppt = new Appointment(name, day, start, end, sAMPM, eAMPM, nodes, this.nodeId);
			// create new log entry with this appt to try to submit
			int logPos = log.size();
			this.newEntry = createLogEntry(newAppt, logPos);
		}
		
		// need to send new LogEntry to leader
		if (newAppt != null && this.nodeId != this.leaderId){
			// increase proposal id before sending
			this.m += this.incAmt;
			// TODO using TCP to send to leader correct? (since we need to be able to check if the leader node is up or down?
		}
		else if (newAppt != null && this.nodeId == this.leaderId){
			// handling for when leader wants to propose a new log entry
			// increase proposal id
			this.m += this.incAmt;
			try{
				// put m into byte array
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				ObjectOutputStream os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.PREPARE.ordinal());
				os.writeInt(this.m);
				byte[] data = outputStream.toByteArray();
				// send promise message to all other nodes
				for (int i = 0; i < this.numNodes; i++){
					if (this.nodeId != i)
						sendPacket(i, data);
				}
			
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		else // newAppt == null, appt conflicts with current calendar
		{
			System.out.println("This appointment conflicts!");
		}
		
	}
	
	/**
	 * create new log entry from currentAppts and the newly created appt
	 * @param newAppt newly create appt
	 * @param logPos position for this new log entry
	 * @return the new log entry
	 */
	public LogEntry createLogEntry(Appointment newAppt, int logPos){
		LogEntry e = new LogEntry(logPos);
		for (Appointment appt:currentAppts){
			e.addAppt(appt);
		}
		e.setUnknown(false);
		e.addAppt(newAppt);
		return e;
	}
	
	/** TODO needs to be updated for Paxos
	 *  deletes appointment based on given appointment ID
	 * @param apptID id for the appointment to be deleted
	 */
	public void deleteOldAppointment(String apptID) {
		Appointment delAppt = null;
		synchronized(lock) {
			for (Appointment appt:this.currentAppts){
				//find corresponding appointment
				if (appt.getApptID().equals(apptID)) {
					delAppt = appt;
				}
			}
			//delete appointment have to do outside iterating on currentAppts
			// because delete() deletes from currentAppts collection
			if (delAppt != null){
				
				
				//clear calendar
				for (Integer id:delAppt.getParticipants()) {
					for (int j = delAppt.getStartIndex(); j < delAppt.getEndIndex(); j++) {
						this.calendars[id][delAppt.getDay().ordinal()][j] = 0;
					}
				}
				//if appt involves other nodes, send msgs
				if (delAppt.getParticipants().size() > 1) {
					for (Integer node:delAppt.getParticipants()) {
						if (node != this.nodeId){
							System.out.println("Send appt deletion to node " + node);
							send(node);
						}
					}
				}
			}
		}
	}
	
	/**
	 * print out the calendar to the terminal
	 */
	public void printCalendar() {
		//now have set of all appointments event records which are currently in calendar
		//next: get eRs by day, and print them
		ArrayList<Appointment> apptList = new ArrayList<Appointment>();
		for (int i = 0; i < 7; i++) {
			System.out.println("------- " + Day.values()[i] + " -------");
			for (Appointment appt:this.currentAppts) {
				if (appt.getDay().ordinal() == i) {
					apptList.add(appt);
				}
			}
			Collections.sort(apptList);
			//print out each day's appointments, ordered by start time
			for (int j = 0; j < apptList.size(); j++) {
				Appointment a = apptList.get(j);
				System.out.println("Appointment name: " + a.getName());
				System.out.println("Appointment ID: " + a.getApptID());
				String partic = "";
				for (int k = 0; k<a.getParticipants().size(); k++) {
					partic = partic.concat(String.valueOf(a.getParticipants().get(k)));
					if (k < (a.getParticipants().size() - 1)) {
						partic = partic.concat(", ");
					}
				}
				System.out.println("Participants: " + partic);
				System.out.println("Start time: " + a.getStart() + " " + a.getsAMPM());
				System.out.println("End time: "+ a.getEnd() + " " + a.geteAMPM());
				System.out.println();
			}
			apptList.clear();
		}
	}
	
	
	/**
	 *  write an event to the log
	 * @param eR the event record to write to log
	 */
	public void writeToLog(){
		// TODO probably delete this and only use saveNodeState() for saving necessary log info
		/*try{
			FileWriter fw = new FileWriter(this.logName, true);
			BufferedWriter bw = new BufferedWriter(fw);
			bw.write("------- new event record -------\n");
			bw.write("Operation: " + eR.getOperation() + "\n");
			bw.write("Node clock: " + eR.getTime() + "\n");
			bw.write("Node Id: " + eR.getNodeId() + "\n");
			bw.write("Appointment to be ");
			if (eR.getOperation().equals("delete"))
				bw.write("deleted from ");
			else
				bw.write("added to ");
			bw.write("dictionary\n");
			bw.write("Appointment name: " + eR.getAppointment().getName() + "\n");
			bw.write("Appointment id: " + eR.getAppointment().getApptID() + "\n");
			bw.write("Day: " + eR.getAppointment().getDay() + "\n");
			bw.write("Start time: " + eR.getAppointment().getStart() + "\n");
			bw.write("End time: " + eR.getAppointment().getEnd() + "\n");
			bw.write("Participants: ");
			for (Integer node:eR.getAppointment().getParticipants()){
				bw.write(node + " ");
			}
			bw.write("\n");
			bw.close();
		}
		catch (IOException e){
			e.printStackTrace();
		}*/
	}
	
	/**
	 *  save state of system for recovering from crash
	 */
	public void saveNodeState(){
		// TODO update this for saving necessary information in case of node crash
		try{
			FileWriter fw = new FileWriter("nodestate.txt", false);  // overwrite each time
			BufferedWriter bw = new BufferedWriter(fw);
			
			// then save the 2D calendar array for each node
			synchronized(lock){
				for (int i = 0; i < this.calendars.length; i++){
					for (int j = 0; j < this.calendars[i].length; j++){
						for (int k = 0; k < this.calendars[i][j].length; k++){
							bw.write(Integer.toString(this.calendars[i][j][k]));
							if (k != this.calendars[i][j].length - 1)
								bw.write(",");
						}
						bw.write("\n");
					}
				}
			}
			
			// save events in NP, PL, NE, currentAppts in following format:
			// operation, time, nodeID, appt name, day, start, end, sAMPM, eAMPM, apptID, participants
			// for days, use ordinals of enums,
			synchronized(lock){
				
				bw.write("current," + currentAppts.size() + "\n");
				for (Appointment appt:currentAppts){
					bw.write(appt.getName() + "," + appt.getDay().ordinal() + "," + appt.getStart() + "," + appt.getEnd() + "," + appt.getsAMPM() + "," + appt.geteAMPM() + ","
							+ appt.getApptID() + ",");
					for (int i = 0; i < appt.getParticipants().size(); i++){
						bw.write(Integer.toString(appt.getParticipants().get(i)));
						if (i != appt.getParticipants().size() - 1)
							bw.write(",");
					}
					bw.write("\n");
				}
			}
			bw.close();
		}
		catch (IOException e){
			e.printStackTrace();
		}
	}
	
	/**
	 *  recover from node failure
	 */
	public void restoreNodeState(){
		// TODO update once saveNodeState() is correct for Paxos implementation
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(this.stateLog));
			String text = null;
			int lineNo = 0;
			int cal = 0;
			int index = 0;
			int tLimit = 7*numNodes + numNodes;
			int npLimit = 0, plLimit = 0, neLimit = 0, apptLimit = 0;
			int numNP = 0, numNE = 0, numPL = 0, numAppt = 0;
		    while ((text = reader.readLine()) != null) {
		    	String[] parts = text.split(",");
		        if (lineNo == 0){ // restore node clock

		        	Appointment.setApptNo(Integer.parseInt(parts[1]));
		        }
		        else if (lineNo > 0 && lineNo <= 7*numNodes ){ // restore calendar
		        		int len = parts.length;
			        	for (int j = 0; j < len; j++){
			        		this.calendars[cal][index][j] = Integer.parseInt(parts[j]);
			        	}
		        	index++;
		        	if (lineNo % 7 == 0){// time to go to next node's calendar
		        		cal++;
		        		index = 0;
		        	}
		        }
		        else if (lineNo > 7*numNodes && lineNo <= tLimit){ // restore T
		        	
		        	index++;
		        }
		        else if (lineNo == tLimit + 1){ 
		        	numNP = Integer.parseInt(parts[1]);
		        	npLimit = lineNo + numNP;
		        }
		        else if (lineNo > tLimit + 1 && lineNo <= npLimit && numNP > 0){ // Restore NP's hashset
		        	ArrayList<Integer> list = new ArrayList<Integer>();
		        	for (int i = 10; i < parts.length; i++)
		        		list.add(Integer.parseInt(parts[i]));
		        	Appointment appt = new Appointment(parts[3], Day.values()[Integer.parseInt(parts[4])], Integer.parseInt(parts[5]), Integer.parseInt(parts[6]), 
		        			parts[7], parts[8], parts[9], list, this.nodeId);
		        	
		        	
		        }
		        else if (lineNo == npLimit + 1){
		        	numPL = Integer.parseInt(parts[1]);
		        	plLimit = lineNo + numPL;
		        }
		        else if (lineNo > npLimit + 1 && lineNo <= plLimit && numPL > 0){ // Restore PL's hashset
		        	ArrayList<Integer> list = new ArrayList<Integer>();
		        	for (int i = 10; i < parts.length; i++)
		        		list.add(Integer.parseInt(parts[i]));
		        	Appointment appt = new Appointment(parts[3], Day.values()[Integer.parseInt(parts[4])], Integer.parseInt(parts[5]), Integer.parseInt(parts[6]), 
		        			parts[7], parts[8], parts[9], list, this.nodeId);
		        	
		        }
		        else if (lineNo == plLimit + 1){
		        	numNE = Integer.parseInt(parts[1]);
		        	neLimit = lineNo + numNE;
		        }
		        else if (lineNo > plLimit + 1 && lineNo <=  neLimit && numNE > 0){ // restore NE's hashset
		        	ArrayList<Integer> list = new ArrayList<Integer>();
		        	for (int i = 10; i < parts.length; i++)
		        		list.add(Integer.parseInt(parts[i]));
		        	Appointment appt = new Appointment(parts[3], Day.values()[Integer.parseInt(parts[4])], Integer.parseInt(parts[5]), Integer.parseInt(parts[6]), 
		        			parts[7], parts[8], parts[9], list, this.nodeId);
		        	
		        }
		        else if (lineNo == neLimit + 1){
		        	numAppt = Integer.parseInt(parts[1]);
		        	apptLimit = lineNo + numAppt;
		        }
		        else if (lineNo > neLimit + 1 && lineNo <= apptLimit && numAppt > 0){ // restore currentAppt hashset
		        	ArrayList<Integer> list = new ArrayList<Integer>();
		        	for (int i = 7; i < parts.length; i++)
		        		list.add(Integer.parseInt(parts[i]));
		        	Appointment appt = new Appointment(parts[0], Day.values()[Integer.parseInt(parts[1])], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), 
		        			parts[4], parts[5], parts[6], list, this.nodeId);
		        	currentAppts.add(appt);
		        }
		        lineNo++;
		    }
		    reader.close();
		} catch (FileNotFoundException e2) {
			e2.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	
	/**
	 *  creates NP, then sends <NP, T> to node k
	 * @param k node to send to
	 */
	public void send(final int k){
		// TODO can use this for leader election stuff, just change what's written to the socket (this already uses TCP)
		try {
			Socket socket = new Socket(hostNames.get(k), port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(0);  // 0 means sending set of events
			
			objectOutput.writeInt(nodeId);
			objectOutput.close();
			out.close();
			socket.close();
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
        
        
	}
	
	/**
	 *  receives <NP, T> from node k
	 * @param clientSocket socket connection to receiving node
	 */
	public void receive(Socket clientSocket){
		// TODO probably can take this to use for a receive for leader election (this is already using TCP), just delete unneeded stuff
		int k = -1;
		//boolean cancellation = false;
		int cancel = -1;
		try {
			// get the objects from the message
			InputStream in = clientSocket.getInputStream();
			ObjectInputStream objectInput = new ObjectInputStream(in);
			cancel = objectInput.readInt();
			k = objectInput.readInt();
			objectInput.close();
			in.close();
		} 
		catch (IOException e) {
			e.printStackTrace();
		} 
		
		// handle the appointments received
		synchronized(lock){
			
			// check for appts in currentAppts that need to be deleted
			HashSet<Appointment> delAppts = new HashSet<Appointment>();
			for (Appointment appt:currentAppts){
					delAppts.add(appt);
					// update calendar
					/*for (Integer id:dR.getAppointment().getParticipants()) {
						for (int j = dR.getAppointment().getStartIndex(); j < dR.getAppointment().getEndIndex(); j++) {
							this.calendars[id][dR.getAppointment().getDay().ordinal()][j] = 0;
						}
					}*/
				}
			
			// now actually remove appointments from currentAppts
			for (Appointment appt:delAppts){
				currentAppts.remove(appt);
			}
			
			saveNodeState();

		}// end synchronize
		
		
		
	}
	
	
	public void sendCancellationMsg(String apptID, final int k){
		// TODO maybe leader node uses something like this to tell another node that the appointment it wants to create has a conflict
		// or should that be done by a UDP packet
		//if (eR != null){
			try {
				Socket socket = new Socket(hostNames.get(k), port);
				OutputStream out = socket.getOutputStream();
				ObjectOutputStream objectOutput = new ObjectOutputStream(out);
				synchronized(lock){
					//objectOutput.writeObject(eR);
					//objectOutput.writeObject(T);
				}
				objectOutput.writeInt(nodeId);
				objectOutput.close();
				out.close();
				socket.close();
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
		//}
	}
	
	/*****  PAXOS specific functions below here *****/
	/**
	 * Determine message type and forward to appropriate function
	 * 
	 * @param packet UDP packet received from another node
	 * @param socket the socket the packet was received from
	 */
	public void receivePacket(DatagramPacket packet){
		// TODO  probably need some sort of queue for handling messages for different log entry
		// i.e. only work on one log entry at a time, keep track with this.logPos
		int senderId = -1;
		if (this.hostNames.contains(packet.getAddress().toString())){
			senderId = this.hostNames.indexOf(packet.getAddress().toString());
		}

		try {
			ByteArrayInputStream byteStream = new ByteArrayInputStream(packet.getData());
		    ObjectInputStream is = new ObjectInputStream(new BufferedInputStream(byteStream));
		    int tmp = is.readInt();
		    MessageType msg = MessageType.values()[tmp];
		    if (msg.equals(MessageType.PREPARE)){
		    	int m = is.readInt();
		    	prepare(m, senderId);
		    }
		    else if (msg.equals(MessageType.PROMISE)){
		    	int accNum = is.readInt();
		    	LogEntry accVal = (LogEntry) is.readObject();
		    	promise(accNum, accVal, senderId);
		    }
		    else if (msg.equals(MessageType.ACCEPT)){
		    	int m = is.readInt();
		    	LogEntry v = (LogEntry) is.readObject();
		    	accept(m, v, senderId);
		    }
		    else if (msg.equals(MessageType.ACK)){
		    	int accNum = is.readInt();
		    	LogEntry accVal = (LogEntry) is.readObject();
		    	ack(accNum, accVal, senderId);
		    }
		    else if (msg.equals(MessageType.COMMIT)){
		    	LogEntry v = (LogEntry) is.readObject();
		    	commit(v);
		    }
		    is.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		catch (ClassNotFoundException e){
			
		}
	      
	}
	
	/**
	 * send data via UDP
	 * @param sendTo id of node to send to
	 * @param data objects saved into byte array to send
	 */
	public void sendPacket(int sendTo, byte[] data){
		try{
			DatagramSocket socket = new DatagramSocket();
			InetAddress address = InetAddress.getByName(this.hostNames.get(sendTo));  // TODO might need to change for using on AWS (i.e. just use IP address)
			DatagramPacket packet = new DatagramPacket(data, data.length, address, this.port);
			socket.send(packet);
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}
	
	/**
	 * received a prepare msg from proposer
	 * @param m
	 * @param logPos
	 * @param senderId proposer's id num
	 */
	public void prepare(int m, int senderId){
		if (m > maxPrepare){
			maxPrepare = m;
			try{
				// put accVal and accNum 
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				ObjectOutputStream os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.PROMISE.ordinal());
				os.writeInt(this.accNum);
				os.writeObject(this.accVal);
				byte[] data = outputStream.toByteArray();
				// send reply with accNum, accVal
				sendPacket(senderId, data);
			
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	
	/** received promise msg from an acceptor node
	 * 
	 * @param accNum accepted proposal number
	 * @param accVal accepted value
	 * @param senderId acceptor's id num
	 */
	public void promise(int accNum, LogEntry accVal, int senderId){
		this.responseVals[senderId] = accVal;
		this.responseNums[senderId] = accNum;
		int totalRecd = 0;
		for (int i = 0; i < this.responseNums.length; i++){
			if (this.responseNums[i] != -1){
				totalRecd++;
			}
		}
		if (totalRecd > this.numNodes/2){ // has received a majority of responses
			int maxNum = 0;
			int index = -1;
			LogEntry v;
			boolean allNull = true;
			for (int i = 0; i < this.responseNums.length; i++){
				if (this.responseNums[i] != -1){
					allNull = false;
				}
				if (this.responseNums[i] > maxNum){
					maxNum = this.responseNums[i];
					index = i;
				}
			}
			if (allNull){
				// choose my own value to send
				//TODO may not be correct
				v = this.accVal;
			}
			else{
				v = this.responseVals[index];
		
			}
			
			// send accept message
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os;
			try {
				os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.ACCEPT.ordinal());
				os.writeInt(this.m);
				os.writeObject(v);
				byte[] data = outputStream.toByteArray();
				// send reply with accNum, accVal
				sendPacket(senderId, data);
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
	}
	
	/**
	 * received accept msg from proposer
	 * @param m proposal number
	 * @param v LogEntry value
	 * @param senderId propser's id number
	 */
	public void accept(int m, LogEntry v, int senderId){
		if (m >= this.maxPrepare){
			this.accNum = m;
			this.accVal = v;
			
			// send ack back to sender
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os;
			try {
				os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.ACK.ordinal());
				os.writeInt(this.accNum);
				os.writeObject(this.accVal);
				byte[] data = outputStream.toByteArray();
				// send reply with accNum, accVal
				sendPacket(senderId, data);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * received ack msg from acceptor
	 * @param accNum the proposal number that's been accepted
	 * @param accVal the LogEntry that's been accepted
	 * @param senderId acceptor's id num
	 */
	public void ack(int accNum, LogEntry accVal, int senderId){
		this.ackRespVals[senderId] = accVal;
		this.ackRespNums[senderId] = accNum;
		// check for responses received
		int totalRecd = 0;
		for (int i = 0; i < this.ackRespNums.length; i++){
			if (this.ackRespNums[i] != -1){
				totalRecd++;
			}
		}
		if (totalRecd > this.numNodes/2){ // has received a majority of responses
			int maxNum = 0;
			int index = -1;
			LogEntry v;
			boolean allNull = true;
			for (int i = 0; i < this.ackRespNums.length; i++){
				if (this.ackRespNums[i] != -1){
					allNull = false;
				}
				if (this.ackRespNums[i] > maxNum){
					maxNum = this.ackRespNums[i];
					index = i;
				}
			}
			if (allNull){
				// choose my own value to send
				//TODO may not be correct
				v = this.accVal;
			}
			else{
				v = this.ackRespVals[index];
		
			}
			
			// send commit message
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os;
			try {
				os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.COMMIT.ordinal());
				os.writeObject(v);
				byte[] data = outputStream.toByteArray();
				// send reply with accNum, accVal
				sendPacket(senderId, data);
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
	}
	
	/**
	 * received commit from the proposer
	 * @param v the log entry to be committed
	 */
	public void commit(LogEntry v){
		this.log.add(v.getLogPos(), v);
		
		// TODO write to storage in case of crash
	}
	
}
