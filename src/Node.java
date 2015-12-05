/**
 * @author Caitlin Ross and Erika Mackin
 *
 * Node object
 */
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.net.*;
import java.io.*;

public class Node {
	private int port;
	private ArrayList<String> hostNames;
	private int nodeId;
	private int numNodes; 
	private String stateLog;
	private int leaderId;
	private int incAmt; // amount to increment m (proposal numbers) by to have unique numbers
	private boolean stillUpdating;
	
	//leader election vars
	private int proposerId;
	private boolean isProposer;
	private int numOks;
	
	
	// Paxos vars
	private int maxPrepare;
	private int accNum;
	private LogEntry accVal;
	private int m;
	private ArrayList<LogEntry> log; // store log entries in order
	private int logPos; // which log position to work on
	private LogEntry newEntry; // entry to try to add 
	private Queue<LogEntry> entryQueue;
	
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
		this.port = port;
		this.hostNames = new ArrayList<String>();
		for (int i = 0; i<hostNames.length; i++){
			this.hostNames.add(hostNames[i]);
		}
		this.nodeId = nodeID;
		this.numNodes = totalNodes;
		this.stateLog = "nodestate.txt";
		this.incAmt = totalNodes;
		this.stillUpdating = false; //TODO make sure to set appropriately when chosen as leader
		
		this.maxPrepare = 0;
		this.accNum = -1;
		this.accVal = null;
		this.m = nodeID;
		this.log = new ArrayList<LogEntry>();
		this.logPos = 0;

		this.newEntry = null;
		this.entryQueue = new PriorityBlockingQueue<LogEntry>(); // orders based on log position

		
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
		

		this.calendars = new int[totalNodes][7][48];
		this.currentAppts = new HashSet<Appointment>();  // keep appointments from most recent log entry
		
		//default: all nodes running, proposer is highest id
		//TODO: should we save who the proposer is when we crash? or 
		//do we automatically assume 4 is the proposer for each 
		//recovered process and if not do an election? 
		this.proposerId = this.numNodes-1;
		if (nodeId == numNodes-1) {
			isProposer = true;
		}
		else {
			isProposer = false;
		}
		this.numOks = 0;

		// recover node state if this is restarting from crash
		if (recovery){
			restoreNodeState();
			updateCalendars(log.get(log.size()-1));
		}
		
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
	
	/**
	 * update calendars and currentAppts based on given log entry
	 * @param e LogEntry to use for updates
	 */
	public void updateCalendars(LogEntry e){
		// clear out currentAppts and calendars
		currentAppts.clear();
		for (int i=0; i < calendars.length; i++){
			for (int j=0; j < calendars[i].length; j++){
				for (int k=0; k<calendars[i][j].length; k++){
					calendars[i][j][k] = 0;
				}
			}
		}

		// update based on given log entry
		for (Appointment a:e.getAppts()){
			currentAppts.add(a);
			int time = a.getStartIndex();
			int endIndex = a.getEndIndex();
			while(time < endIndex){
				for(Integer node:a.getParticipants()){
					this.calendars[node][a.getDay().ordinal()][time] = 1;
				}
				time++;
			}
		}
		
	}

	
	/** creates a new appointment from the given info
	 * if this node is leader, check for calendar conflict; if none start paxos
	 * if node != leader, send to leader who will check for conflict 
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
		LogEntry newEntry = null;
		
		/*get calendar value and currentAppt list from last logEntry in log, 
		*if log is empty, calendar is all zeros  and apptList is empty by default
		*don't need to clear these values if new calendar isn't accepted by Paxos
		*since it will be overwritten each time anyway, only successful versions 
		*get saved to new logEntry
		*/
		if (log.size() > 0) {
			calendars = log.get(log.size()-1).getCalendar();
			currentAppts = log.get(log.size()-1).getAppts();
		}
		
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
			newEntry = createLogEntry(newAppt, logPos, this.nodeId);
			//currentAppts.add(newAppt); // TODO don't think this is needed
		}		
		
		// need to send new LogEntry to leader
		if (newEntry != null && this.nodeId != this.leaderId){
			// increase proposal id before sending
			this.m += this.incAmt;
			saveNodeState();
			sendProposal(newEntry);
		}
		else if (newEntry != null && this.nodeId == this.leaderId){
			// handling for when leader wants to propose a new log entry
			this.logPos = newEntry.getLogPos();
			if (stillUpdating){
				// push this onto queue
				entryQueue.offer(newEntry);
			}
			else{
				// can start with accept phase because we're up to date on log
				this.m += this.incAmt;
				saveNodeState();
				if (entryQueue.isEmpty()){
					startPaxos(newEntry);
				}
				else{
					entryQueue.offer(newEntry);
					startPaxos(entryQueue.poll());
				}
			}
			time++;
		}
		else // newAppt == null, appt conflicts with current calendar
		{
			System.out.println("This appointment conflicts!");
		}
		
		// TODO reconcile this block with the previous if/else block
		/*// send message to distinguished proposer, unless self is proposer
		if (proposerId != nodeId ) {
			int success = -1;
			success = sendCalendar(proposerId, 4, calendars, currentAppts);
			if (success != 1) {
				election();
				sendCalendar(proposerId, 4, calendars, currentAppts);
			}
		}
		else {
			//run paxos
		}*/
		

	}
	
	/**
	 * create new log entry from currentAppts and the newly created appt
	 * @param newAppt newly create appt
	 * @param logPos position for this new log entry
	 * @return the new log entry
	 */
	public LogEntry createLogEntry(Appointment newAppt, int logPos, int owner){
		LogEntry e = new LogEntry(logPos, owner);
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
		//get calendar and appt list from latest log entry
		if (log.size() > 0) {
			calendars = log.get(log.size()-1).getCalendar();
			currentAppts = log.get(log.size()-1).getAppts();
		}
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
				// send message to distinguished proposer, unless self is proposer
				if (proposerId != nodeId ) {
					int success = -1;
					//success = sendCalendar(proposerId, 4, calendars, currentAppts);

					//leader is down, run leader election and try again
					if (success != 1) {
						election();
						//sendCalendar(proposerId, 4, calendars, currentAppts);
					}
				}
				else {
					//run paxos
				}

			}
		}
	}
	
	/**
	 * elects new leader 
	 */
	public void election() {
		int success = -1;
		int successSum = 0;
		
		//assume self is leader until get an ok message from someone else
		//isProposer = true;
		//proposerId = nodeId;
		for (int i=0; i < numNodes; i++) {
			if (i > nodeId){
				//send 'election' to all nodes with higher ids
				//success = 1 if message sent, 0 if other node is down
				send(i, MessageType.ELECTION);

				//tally number of successful messages sent
				successSum += success;
				
				
			}
		}
		if (successSum == 0) {
			//all other nodes down, is leader by default;
			isProposer = true;
			proposerId = nodeId;
			//notify everyone, even down nodes that self is new leader
			for (int i=0; i<numNodes; i++) {
				if (i != nodeId) {
					send(i, MessageType.COORDINATOR);
				}
			}
			
		}
		
	}
	
	
	/**
	 * used for election and to add/delete appts
	 *  sends 'Election' and sender id to node k
	 * @param k node to send to
	 */
	public int send(final int k, MessageType msgType){

		try {
			Socket socket = new Socket(hostNames.get(k), port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(msgType.ordinal()); //1 - election, 2 - ok, 3 - coordinator
			objectOutput.writeInt(nodeId);
			objectOutput.close();
			out.close();
			socket.close();
			return 1;
		} 
		catch (ConnectException | UnknownHostException ce){
			return 0;
		}

		catch (IOException e) {
			e.printStackTrace();
			return 0;
		}
           
	}
	
	// TODO probably delete?
	/*public int sendCalendar(final int k, int msgType, int[][][] cal, Set<Appointment> appts){

		try {
			Socket socket = new Socket(hostNames.get(k), port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(0);  // 0 means sending set of events
			objectOutput.writeInt(msgType); //1 - election, 2 - ok, 3 - coordinator
			objectOutput.writeInt(nodeId);
			objectOutput.writeObject(cal);
			objectOutput.writeObject(appts);
			objectOutput.close();
			out.close();
			socket.close();
			return 1;
		} 
		catch (ConnectException | UnknownHostException ce){
			return 0;
		}

		catch (IOException e) {
			e.printStackTrace();
			return 0;
		}
           
	}*/
	
	
	
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
	 *  save state of system for recovering from crash
	 */
	public void saveNodeState(){
		// update this for saving necessary information in case of node crash
		try{
			FileWriter fw = new FileWriter("nodestate.txt", false);  // overwrite each time
			BufferedWriter bw = new BufferedWriter(fw);
			
			bw.write(this.m + "\n");
			
			// save log
			for (LogEntry e:this.log){
				bw.write(e.toString());
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
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(this.stateLog));
			String text = null;
			int lineNo = 0;
			LogEntry e = null;
		    while ((text = reader.readLine()) != null) {
		    	String[] parts = text.split(",");
		        if (lineNo == 0){ // restore node clock
		        	this.m = Integer.parseInt(parts[0]);
		        	
		        }
		        else{ 
		        	if (text.startsWith("LogEntry")){
		        		if (e != null){
		        			// done adding appts for previous log entry
		        			log.add(e); 
		        		}
		        		e = LogEntry.fromString(text);
		        	}
		        	else if (text.startsWith("Appointment")){
		        		e.addAppt(Appointment.fromString(text));
		        	}
		        		
		        }
		        lineNo++;
		    }
		    log.add(e); //add the last log entry to the log
 		    reader.close();
		} catch (FileNotFoundException e2) {
			e2.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 *  send proposed LogEntry to the distingushed proposer/leader
	 *  @param entry log entry to be proposed
	 */
	public void sendProposal(LogEntry entry){
		try {
			Socket socket = new Socket(hostNames.get(this.leaderId), port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(MessageType.PROPOSE.ordinal());  
			objectOutput.writeInt(nodeId);
			objectOutput.writeObject(entry); // entry should contain correct logPosition, so no need to send separately
			objectOutput.close();
			out.close();
			socket.close();
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
        
        
	}

	
	/**
	 *  receives TCP messages from other nodes
	 * @param clientSocket socket connection to receiving node
	 */
	public void receive(Socket clientSocket){
		// at the moment, TCP should only receive PROPOSE and CONFLICT messages
		MessageType msg;
		int senderId;
		LogEntry entry = null;
		try {
			// get the objects from the message
			InputStream in = clientSocket.getInputStream();
			ObjectInputStream objectInput = new ObjectInputStream(in);
			int tmp = objectInput.readInt();
			msg = MessageType.values()[tmp];
			if (msg.equals(MessageType.PROPOSE)){
				senderId = objectInput.readInt();
				entry = (LogEntry) objectInput.readObject();
				if (entryQueue.isEmpty())
					checkProposal(entry);
				else {
					entryQueue.offer(entry);
					checkProposal(entryQueue.poll());
				}
			}
			else if (msg.equals(MessageType.CONFLICT)){
				// node received conflict message from the leader
				entry = (LogEntry) objectInput.readObject(); // this is most recent log entry
				// add to log and update the calendars
				this.log.add(entry.getLogPos(), entry);
				saveNodeState();
				updateCalendars(entry);
				
				// TODO report that appointment to be added has a conflict to user, or not;
				// without it will update on the node and user will be able to view up to date calendar
			}
			else if (msg.equals(MessageType.ELECTION)){
				int sender = objectInput.readInt();
				if (nodeId > sender){
					send(sender, MessageType.OK);
					election();
				}
			}
			else if (msg.equals(MessageType.OK)){
				isProposer = false;
			}
			else if (msg.equals(MessageType.COORDINATOR)){
				int sender = objectInput.readInt();
				proposerId = sender;
				isProposer = false;
			}
			
			objectInput.close();
			in.close();
		} 
		catch (IOException | ClassNotFoundException e) {
			e.printStackTrace();
		} 
		
	}
	
	public void checkProposal(LogEntry newEntry){
		// check if this log entry is feasible 
		// save newEntry's appts into a temp set
		int senderId = newEntry.getOwner();
		HashSet<Appointment> tmpSet = new HashSet<Appointment>();
		for (Appointment a:newEntry.getAppts()){
			tmpSet.add(a);
		}
		
		// create a tmpCal for checking for conflicts
		int[][][] tmpCal = new int[numNodes][7][48];
		
		// for each appt in currentAppts, if appt in tmpAppts, delete from tmpAppts
		// else remember that this is a deleted appointment
		for (Appointment a:currentAppts){
			if (tmpSet.contains(a)){
				// update tmp cal when a is in both sets
				int time = a.getStartIndex();
				int end = a.getEndIndex();
				while (time < end){
					for (Integer node:a.getParticipants()){
						tmpCal[node][a.getDay().ordinal()][time] = 1;
					}
					time++;
				}
				tmpSet.remove(a);
			}
			else {// not in tmpSet, means this appointment has been deleted
				// don't add to tmpCal
				// TODO handle this appropriately
			}
		}
		
		// any remaining appts in tmpAppts are new and should be checked for conflicts
		boolean conflict = false;
		if (!tmpSet.isEmpty()){
			for (Appointment a:tmpSet){
				// check for conflicts against tmpCal
				int time = a.getStartIndex();
				int end = a.getEndIndex();
				while (time < end){
					for (Integer node:a.getParticipants()){
						if (tmpCal[node][a.getDay().ordinal()][time] == 1){
							conflict = true;
							break;
						}
					}
					
				}
				if (conflict)
					break;
			}
		}
		
		if (conflict){
			// send msg to node that there's a conflict
			if (!this.log.get(this.log.size()-1).isUnknown()) // make sure that leader actually has this log entry's info
				sendConflictMsg(senderId, this.log.get(this.log.size()-1)); 
			else  // for some reason, leader doesn't have info, shouldn't happen
				System.out.println("SOMETHING'S WRONG! leader doesn't have most up to date calendar");
		}
		else {
			// start Paxos from the accept phase
			this.m += this.incAmt;
			saveNodeState();
			startPaxos(newEntry);
		}
		
	}
	
	/**
	 * send conflict message to node k
	 * @param k
	 * @param entry 
	 */
	public void sendConflictMsg(int k, LogEntry entry){
		// leader node uses this to notify node k that it's log entry conflicts/can't run it thru Paxos
		try {
			Socket socket = new Socket(hostNames.get(k), port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(MessageType.CONFLICT.ordinal());
			objectOutput.writeObject(entry);
			objectOutput.close();
			out.close();
			socket.close();
		} 
		catch (IOException e) {
			e.printStackTrace();
		}
		

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
		
		// TODO change this back when done testing on my personal computers
		int senderId = -1;
		if (this.nodeId == 0)
			senderId = 1;
		else
			senderId = 0;
		//if (this.hostNames.contains(packet.getAddress().toString())){
		//	senderId = this.hostNames.indexOf(packet.getAddress().toString());
		//}

		try {
			ByteArrayInputStream byteStream = new ByteArrayInputStream(packet.getData());
		    ObjectInputStream is = new ObjectInputStream(new BufferedInputStream(byteStream));
		    int tmp = is.readInt();
		    MessageType msg = MessageType.values()[tmp];
		    System.out.println("Received " + msg + " msg from node " + senderId);
		    if (msg.equals(MessageType.PREPARE)){
		    	int m = is.readInt();
		    	int logPos = is.readInt();
		    	prepare(m, logPos, senderId);
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
	
	/** TODO needs to be called when leader is selected!
	 * leader should use this at beginning or whenever a new leader is selected
	 * to get all log entries
	 * 
	 */
	public void getUpdates(){
		for (LogEntry entry:log){
			if (entry.isUnknown()){ // need to get information about this entry
				// kick-off synod alg for each log position that the leader doesn't have info for
				this.m += this.incAmt;
				saveNodeState();
				startPaxos(entry.getLogPos()); 
			}
			
		}
		
		// could be newer entries at end of log array that other nodes know about but not this one
		// execute synod algorithm to see if this is true
		// will know for certain after receiving enough promise msgs
		this.logPos = this.log.size();
		this.m += this.incAmt;
		saveNodeState();
		startPaxos(this.logPos);
	}
	
	/**
	 * send new prepare msg to all other nodes
	 * full paxos, to allow for updates to log for new leaders
	 * @param logPos the position to get info for
	 */
	public void startPaxos(int logPos){
		try{
			// put m into byte array
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os = new ObjectOutputStream(outputStream);
			os.writeInt(MessageType.PREPARE.ordinal());
			os.writeInt(this.m);
			os.writeInt(logPos);
			os.flush();
			byte[] data = outputStream.toByteArray();
			// send promise message to all other nodes
			for (int i = 0; i < this.numNodes; i++){
				if (this.nodeId != i) {
					System.out.println("Sending PREPARE msg to node " + i);
					sendPacket(i, data);
				}
			}
		
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * this method should be used when the leader is up to date on the whole log
	 * this is optimization to only do accept-ack-commit phase
	 * @param v
	 */
	public void startPaxos(LogEntry v){
		// send accept message
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ObjectOutputStream os;
		try {
			os = new ObjectOutputStream(outputStream);
			os.writeInt(MessageType.ACCEPT.ordinal());
			os.writeInt(m);
			os.writeObject(v);
			os.flush();
			byte[] data = outputStream.toByteArray();
			// send reply with m and v
			for (int i = 0; i < this.numNodes; i++){
				if (this.nodeId != i) {
					System.out.println("Sending ACCEPT msg to node " + i);
					sendPacket(i, data);
				}
			}
			
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
	public void prepare(int m, int logPos, int senderId){
		if (m > maxPrepare){
			maxPrepare = m;
			try{
				// put accVal and accNum 
				ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
				ObjectOutputStream os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.PROMISE.ordinal());
				os.writeInt(this.accNum);
				os.writeObject(this.accVal);
				os.flush();
				byte[] data = outputStream.toByteArray();
				// send reply with accNum, accVal
				System.out.println("Sending PROMISE msg to node " + senderId);
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
		if (totalRecd > (this.numNodes-1)/2){ // has received a majority of responses
			int maxNum = 0;
			int index = -1;
			LogEntry v = null;
			boolean allNull = true;
			
			for (int i = 0; i < this.responseVals.length; i++){
				// check if all values are null
				if (this.responseVals[i] != null){
					allNull = false;
				}
				// find largest accNum to choose correct accVal
				if (this.responseNums[i] > maxNum){
					maxNum = this.responseNums[i];
					index = i;
				}
			}
			if (allNull){ // no value has been accepted for this log entry
				stillUpdating = false;
				// choose my own value to send
				// TODO probably need to pull this off of a queue
				if (!entryQueue.isEmpty())
					v = entryQueue.poll();
				else // I think this shouldn't happen 
					// or maybe it means that a new leader selected, it's done updating, but no one has requested a new log entry?
					System.out.println("SOMETHING'S WRONG: no log entry available");
			}
			else if (stillUpdating && !allNull){
				v = this.responseVals[index];
			}
			else{ // not still updating after leader election, but at least 1 node has proposed value
				// not sure if this condition is possible
				// TODO figure this out; is this correct
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
				os.flush();
				byte[] data = outputStream.toByteArray();
				// send reply with m and v
				for (int i = 0; i < this.numNodes; i++){
					if (this.nodeId != i) {
						System.out.println("Sending ACCEPT msg to node " + i);
						sendPacket(i, data);
					}
				}
				
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
				os.flush();
				byte[] data = outputStream.toByteArray();
				// send reply with accNum, accVal
				System.out.println("Sending ACK msg to node " + senderId);
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
		if (totalRecd > (this.numNodes-1)/2){ // has received a majority of responses
			// at this point, all ack msgs received for this logPosition should have same accVal
			LogEntry v = accVal;
			
			// update proposing node's calendars
			this.log.add(v.getLogPos(), v);
			updateCalendars(v);
			saveNodeState();
			
			// send commit message
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			ObjectOutputStream os;
			try {
				os = new ObjectOutputStream(outputStream);
				os.writeInt(MessageType.COMMIT.ordinal());
				os.writeObject(v);
				os.flush();
				byte[] data = outputStream.toByteArray();
				// send reply with v
				for (int i = 0; i < this.numNodes; i++){
					if (this.nodeId != i) {
						System.out.println("Sending COMMIT msg to node " + i);
						sendPacket(i, data);
					}
				}
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
		//  need to update currentAppts and calendar stuff based on this new entry
		updateCalendars(v);
		// write to storage in case of crash
		saveNodeState();
	}
	
}
