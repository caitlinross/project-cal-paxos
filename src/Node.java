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
	private String[] hostNames;
	private int nodeId;
	private int numNodes; 
	private String logName;
	private String stateLog;
	
	// variables that need to be concerned with synchronization
	private Object lock = new Object();
	private int calendars[][][];
	private Set<EventRecord> PL;
	private Set<EventRecord> NE;
	private Set<EventRecord> NP;
	private Set<Appointment> currentAppts;
	private int T[][];
	private int c;
	private boolean sendFail[];
	private boolean cantSched;
	private Set<Appointment> badAppts;
	
	/**
	 * @param totalNodes number of nodes being used
	 * @param port port to use for connections
	 * @param hostNames hostnames for all nodes
	 * @param nodeID this nodes id number
	 * @param recovery is this a recovery startup?
	 * @param logName the file name for the log
	 * @param stateLog the file name for saving the node state
	 * @param calendars stores a 0 or 1 for each time period to represent appointment or free
	 * @param PL from W&B alg
	 * @param NE from W&B alg
	 * @param NP from W&B alg
	 * @param currentAppts appointments that are scheduled; only good appointments (At least as far as this node knows so far)
	 * @param badAppts conflicting appointments that need to be reported to the creating node
	 * @param T matrix from W&B alg
	 * @param c clock for this node
	 * @param sendFail for keeping track of whether we were able to send to nodes or not (if they crashed)
	 * @param cantSched flag for determing if there has been some conflicting appointments
	 */
	public Node(int totalNodes, int port, String[] hostNames, int nodeID, boolean recovery) {
		this.logName = "appointments.log";
		this.stateLog = "nodestate.txt";
		this.nodeId = nodeID;
		this.numNodes = totalNodes;
		this.port = port;
		this.hostNames = hostNames;
		
		this.calendars = new int[totalNodes][7][48];
		this.PL = new HashSet<EventRecord>();
		this.NE = new HashSet<EventRecord>();
		this.NP = new HashSet<EventRecord>();
		this.currentAppts = new HashSet<Appointment>();  // dictionary
		this.badAppts = new HashSet<Appointment>();
		this.T = new int[totalNodes][totalNodes];
		this.c = 0;
		this.sendFail = new boolean[this.numNodes];
		for (int i = 0; i < sendFail.length; i++){
			sendFail[i] = false;
		}
		this.setCantSched(false);
		
		// recover node state if this is restarting from crash
		if (recovery)
			restoreNodeState();
		
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
			insert(newAppt);
		}
		
		// appointment involves other nodes besides itself; need to send messages
		if (nodes.size() > 1 && newAppt != null){
			for (Integer node:nodes){
				if (node != this.nodeId){
					System.out.println("Send new appt to node " + node);
					send(node);
				}
			}
		}
		
	}
	
	/**
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
				delete(delAppt);
				
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
	 *  deletes a conflicting appointment from self and sends messages to any other necessary nodes
	 * @param appt appointment to be deleted
	 * @param notifyingNode the node that notified about the conflict
	 */
	public void deleteOldAppointment(Appointment appt, int notifyingNode) {
		delete(appt);
		for (Integer node:appt.getParticipants()){
			if (node != notifyingNode && node != this.nodeId){
				sendCancellationMsg(appt, node);
			}
			else if (node == notifyingNode){
				sendCancellationMsg(appt.getApptID(), notifyingNode);
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
	public void writeToLog(EventRecord eR){
		try{
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
		}
	}
	
	/**
	 *  save state of system for recovering from crash
	 */
	public void saveNodeState(){
		try{
			FileWriter fw = new FileWriter("nodestate.txt", false);  // overwrite each time
			BufferedWriter bw = new BufferedWriter(fw);
			
			// line 1: c
			synchronized(lock){
				bw.write(this.c + "," + Appointment.getApptNo() + "\n");
			}
			
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
			
			// save T
			synchronized(lock){
				for (int i = 0; i < this.T.length; i++){
					for (int j = 0; j < this.T[i].length; j++){
						bw.write(Integer.toString(this.T[i][j]));
						if (j != this.T[i].length - 1){
							bw.write(",");
						}
					}
					bw.write("\n");
				}
			}
			
			// save events in NP, PL, NE, currentAppts in following format:
			// operation, time, nodeID, appt name, day, start, end, sAMPM, eAMPM, apptID, participants
			// for days, use ordinals of enums,
			synchronized(lock){
				bw.write("NP," + NP.size() + "\n");
				for (EventRecord eR:NP){
					bw.write(eR.getOperation() + "," + eR.getTime() + "," + eR.getNodeId() + "," + eR.getAppointment().getName() + "," + eR.getAppointment().getDay().ordinal() + ","
							+ eR.getAppointment().getStart() + "," + eR.getAppointment().getEnd() + "," + eR.getAppointment().getsAMPM() + "," + eR.getAppointment().geteAMPM() + "," 
							+ eR.getAppointment().getApptID() + ",");
					for (int i = 0; i < eR.getAppointment().getParticipants().size(); i++){
						bw.write(Integer.toString(eR.getAppointment().getParticipants().get(i)));
						if (i != eR.getAppointment().getParticipants().size() - 1)
							bw.write(",");
					}
					bw.write("\n");
				}
				
				bw.write("PL," + PL.size() + "\n");
				for (EventRecord eR:PL){
					bw.write(eR.getOperation() + "," + eR.getTime() + "," + eR.getNodeId() + "," + eR.getAppointment().getName() + "," + eR.getAppointment().getDay().ordinal() + ","
							+ eR.getAppointment().getStart() + "," + eR.getAppointment().getEnd() + "," + eR.getAppointment().getsAMPM() + "," + eR.getAppointment().geteAMPM() + ","
							+ eR.getAppointment().getApptID() + ",");
					for (int i = 0; i < eR.getAppointment().getParticipants().size(); i++){
						bw.write(Integer.toString(eR.getAppointment().getParticipants().get(i)));
						if (i != eR.getAppointment().getParticipants().size() - 1)
							bw.write(",");
					}
					bw.write("\n");
				}
				
				bw.write("NE," + NE.size() + "\n");
				for (EventRecord eR:NE){
					bw.write(eR.getOperation() + "," + eR.getTime() + "," + eR.getNodeId() + "," + eR.getAppointment().getName() + "," + eR.getAppointment().getDay().ordinal() + ","
							+ eR.getAppointment().getStart() + "," + eR.getAppointment().getEnd() + "," + eR.getAppointment().getsAMPM() + "," + eR.getAppointment().geteAMPM() + ","
							+ eR.getAppointment().getApptID() + ",");
					for (int i = 0; i < eR.getAppointment().getParticipants().size(); i++){
						bw.write(Integer.toString(eR.getAppointment().getParticipants().get(i)));
						if (i != eR.getAppointment().getParticipants().size() - 1)
							bw.write(",");
					}
					bw.write("\n");
				}
				
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
		        	this.c = Integer.parseInt(parts[0]);
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
		        	for (int i = 0; i < this.T.length; i++){
		        		T[index][i] = Integer.parseInt(parts[i]);
		        	}
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
		        	EventRecord eR = new EventRecord(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), appt);
		        	NP.add(eR);
		        	
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
		        	EventRecord eR = new EventRecord(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), appt);
		        	PL.add(eR);
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
		        	EventRecord eR = new EventRecord(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), appt);
		        	NE.add(eR);
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
	 *  insert appointment into dictionary
	 * @param appt appointment to be inserted
	 */
	public void insert(Appointment appt){
		this.c++;
		this.T[this.nodeId][this.nodeId] = c;
		EventRecord eR = new EventRecord("insert", c, nodeId, appt);
		writeToLog(eR);
		synchronized(lock){
			PL.add(eR);
			currentAppts.add(appt);
			saveNodeState();
		}
	}
	
	/**
	 *  delete appointment from dictionary
	 * @param appt appointment to be deleted
	 */
	public void delete(Appointment appt){
		this.c++;
		this.T[this.nodeId][this.nodeId] = c;
		EventRecord eR = new EventRecord("delete", c, nodeId, appt);
		writeToLog(eR);
		synchronized(lock){
			PL.add(eR);
			Appointment delAppt = null;
			for (Appointment a:currentAppts){
				if (a.getApptID().equals(appt.getApptID())){
					delAppt = a;
				}
			}
			if (delAppt != null)
				currentAppts.remove(delAppt);
			saveNodeState();
		}
	}
	
	/**
	 *  checks if we know if node k has learned about event e
	 * @param Ti T matrix from node k
	 * @param eR event record to check
	 * @param k node id we want to check our knowledge of
	 * @return true when we know that site k knows about this event
	 */
	public boolean hasRec(int Ti[][], EventRecord eR, int k){		
		return Ti[k][eR.getNodeId()] >= eR.getTime();
	}
	
	/**
	 *  creates NP, then sends <NP, T> to node k
	 * @param k node to send to
	 */
	public void send(final int k){
		// create NP to send
		NP.clear();
		synchronized(lock){
			for (EventRecord eR:PL){
				if (!hasRec(this.T, eR, k)){
					NP.add(eR);
				}
			}
			saveNodeState();
		}
		
		// now send NP
		try {
			Socket socket = new Socket(hostNames[k], port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(0);  // 0 means sending set of events
			synchronized(lock){
				objectOutput.writeObject(NP);
				objectOutput.writeObject(T);
			}
			objectOutput.writeInt(nodeId);
			objectOutput.close();
			out.close();
			socket.close();
			sendFail[k] = false;
		} 
		catch (ConnectException | UnknownHostException ce){
			// send to process k failed
			// create thread to keep trying
			if (!sendFail[k]){  // only start if this hasn't already started
				sendFail[k] = true;
			
				// start a thread that periodically checks for k to recover and send again
				Runnable runnable = new Runnable() {
                    public synchronized void run() {
                    	while (sendFail[k]){
	                        try {
								Thread.sleep(6000);  
								send(k);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                    	}
                    }
                };
                new Thread(runnable).start();
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
        
        
	}
	
	/**
	 *  receives <NP, T> from node k
	 * @param clientSocket socket connection to receiving node
	 */
	@SuppressWarnings("unchecked")
	public void receive(Socket clientSocket){
		Set<EventRecord> NPk = null;
		int Tk[][] = null;
		int k = -1;
		Appointment cancelAppt = null;
		EventRecord cancelER = null;
		//boolean cancellation = false;
		int cancel = -1;
		try {
			// get the objects from the message
			InputStream in = clientSocket.getInputStream();
			ObjectInputStream objectInput = new ObjectInputStream(in);
			cancel = objectInput.readInt();
			if (cancel == 0){
				//cancellation = false;
				NPk = (HashSet<EventRecord>)objectInput.readObject();
				Tk = (int[][])objectInput.readObject();
			}
			else if (cancel == 1){
				//cancellation = true;
				cancelAppt = (Appointment)objectInput.readObject();
			}
			else if (cancel == 2){
				cancelER = (EventRecord)objectInput.readObject();
			}
			k = objectInput.readInt();
			objectInput.close();
			in.close();
		} 
		catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} 
		
		if (cancel == 0){
			// handle the appointments received
			if (NPk != null){
				synchronized(lock){
					// update NE
					NE.clear();
					for (EventRecord fR:NPk){
						if (!hasRec(T, fR, nodeId)){
							NE.add(fR);
						}
					}
					
					// update the dictionary and calendar and log
					// check for appts in currentAppts that need to be deleted
					HashSet<Appointment> delAppts = new HashSet<Appointment>();
					for (Appointment appt:currentAppts){
						EventRecord dR = containsAppointment(NE, appt);
						if (dR != null && dR.getOperation().equals("delete")){
							delAppts.add(appt);
							// update calendar
							for (Integer id:dR.getAppointment().getParticipants()) {
								for (int j = dR.getAppointment().getStartIndex(); j < dR.getAppointment().getEndIndex(); j++) {
									this.calendars[id][dR.getAppointment().getDay().ordinal()][j] = 0;
								}
							}
						}
					}
					// now actually remove appointments from currentAppts
					for (Appointment appt:delAppts){
						currentAppts.remove(appt);
					}
					// check for events in NE that need to be inserted into currentAppts
					for (EventRecord eR:NE){
						writeToLog(eR); // want to write all events in NE to log
						if (eR.getOperation().equals("insert")){
							EventRecord dR = containsAppointment(NE, eR.getAppointment());
							if (dR == null){ // there's no 'delete()' for this appointment so add to currentAppts
								// first check to see if the appointment conflicts with my schedule
								// then go through other nodes calendars and handle appropriately
								if (eR.getAppointment().getParticipants().contains(this.nodeId)){
									boolean conflict = false;
									for (int j = eR.getAppointment().getStartIndex(); j < eR.getAppointment().getEndIndex(); j++) {
										if (this.calendars[this.nodeId][eR.getAppointment().getDay().ordinal()][j] == 1){
											conflict = true;
										}
									}
									if (conflict){
										System.out.println("Trying to schedule conflicting appointment");
										sendCancellationMsg(eR.getAppointment(), k);
									}
									else {
										currentAppts.add(eR.getAppointment());
										// update my view of all participants calendars
										for (Integer id:eR.getAppointment().getParticipants()) {
											for (int j = eR.getAppointment().getStartIndex(); j < eR.getAppointment().getEndIndex(); j++) {
												this.calendars[id][eR.getAppointment().getDay().ordinal()][j] = 1;
											}
										}
									}
								}
								else {  // this node isn't a participant, shouldn't need to check for conflict 
									// checking for conflicts is left up to participants of an appointment
									currentAppts.add(eR.getAppointment());
									// update my view of all participants calendars
									for (Integer id:eR.getAppointment().getParticipants()) {
										for (int j = eR.getAppointment().getStartIndex(); j < eR.getAppointment().getEndIndex(); j++) {
											this.calendars[id][eR.getAppointment().getDay().ordinal()][j] = 1;
										}
									}
								}
								
							}
						}
					}
					
					// update T
					for (int i = 0; i < numNodes; i++){
						T[nodeId][i] = Math.max(T[nodeId][i], Tk[k][i]);
					}
					for (int i = 0; i < numNodes; i++){
						for (int j = 0; j < numNodes; j++){
							T[i][j] = Math.max(T[i][j], Tk[i][j]);
						}
					}
					
					// updates to PL
					HashSet<EventRecord> delPL = new HashSet<EventRecord>();
					for (EventRecord eR:PL){
						boolean canDel = true;
						for (int j = 0; j < numNodes; j++){
							if (!hasRec(T, eR, j)){
								canDel = false;
							}
						}
						if (canDel)
							delPL.add(eR);
					}
					for (EventRecord eR:delPL){
						PL.remove(eR);
					}
					
					for (EventRecord eR:NE){
						for (int j = 0; j < numNodes; j++){
							if (!hasRec(T, eR, j)){
								PL.add(eR);
							}
						}
					}
					
					saveNodeState();
	
				}// end synchronize
			}
		}
		else if (cancel == 1) { // received appointment to be cancelled because of conflict
			boolean found = false;
			synchronized(lock){
				// check that appointment isn't already in badAppts
				for (Appointment a:badAppts){
					if (a.getApptID().equals(cancelAppt.getApptID()))
						found = true;
				}
				if (!found){
					this.setCantSched(true);
					if (cancelAppt != null)
						badAppts.add(cancelAppt);
				}
			}
			if (!found && cancelAppt != null)
				deleteOldAppointment(cancelAppt, k);
		}
		else if (cancel == 2){
			boolean found = false;
			synchronized(lock){
				for (EventRecord fR:PL){
					if (fR.getAppointment().getApptID().equals(cancelER.getAppointment().getApptID()) && fR.getOperation().equals("delete"))
						found = true;
				}
				if (!found){
					writeToLog(cancelER);
					PL.add(cancelER);
				}
			}
			saveNodeState();
		}
		
	}
	
	/**
	 *  send a message to node k that this appointment conflicts with previously scheduled node
	 * @param appt appointment to cancel
	 * @param k node to notify (should be the node that originally created appointment)
	 */
	public void sendCancellationMsg(Appointment appt, final int k){
		try {
			Socket socket = new Socket(hostNames[k], port);
			OutputStream out = socket.getOutputStream();
			ObjectOutputStream objectOutput = new ObjectOutputStream(out);
			objectOutput.writeInt(1);  // 1 means sending specific appointment to be canceled
			synchronized(lock){
				objectOutput.writeObject(appt);
				//objectOutput.writeObject(T);
			}
			objectOutput.writeInt(nodeId);
			objectOutput.close();
			out.close();
			socket.close();
			sendFail[k] = false;
		} 
		catch (ConnectException | UnknownHostException ce){
			// send to process k failed
			if (!sendFail[k]){  // only start if this hasn't already started
				sendFail[k] = true;
			
				// start a thread that periodically checks for k to recover and send again
				Runnable runnable = new Runnable() {
                    public synchronized void run() {
                    	while (sendFail[k]){
	                        try {
								Thread.sleep(6000); 
								send(k);
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                    	}
                    }
                };
                new Thread(runnable).start();
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void sendCancellationMsg(String apptID, final int k){
		EventRecord eR = null;
		for (EventRecord fR:PL){
			if (fR.getAppointment().getApptID().equals(apptID) && fR.getOperation().equals("delete"))
				eR = fR;
		}
		if (eR != null){
			try {
				Socket socket = new Socket(hostNames[k], port);
				OutputStream out = socket.getOutputStream();
				ObjectOutputStream objectOutput = new ObjectOutputStream(out);
				objectOutput.writeInt(2);  // 2 means sending back delete event record to each notifying node
				synchronized(lock){
					objectOutput.writeObject(eR);
					//objectOutput.writeObject(T);
				}
				objectOutput.writeInt(nodeId);
				objectOutput.close();
				out.close();
				socket.close();
				sendFail[k] = false;
			} 
			catch (ConnectException | UnknownHostException ce){
				// send to process k failed
				if (!sendFail[k]){  // only start if this hasn't already started
					sendFail[k] = true;
				
					// start a thread that periodically checks for k to recover and send again
					Runnable runnable = new Runnable() {
	                    public synchronized void run() {
	                    	while (sendFail[k]){
		                        try {
									Thread.sleep(6000); 
									send(k);
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
	                    	}
	                    }
	                };
	                new Thread(runnable).start();
				}
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/**
	 *  determine if a given appointment is the same as one in an EventRecord
	 *  useful when trying to find the insert or delete of a given appointment
	 * @param set The set to iterate through
	 * @param appt the appointment to find
	 * @return returns the appropriate event record if found, otherwise null
	 */
	public static EventRecord containsAppointment(Set<EventRecord> set, Appointment appt){
		for (EventRecord eR:set){
			if (eR.getAppointment().getApptID().equals(appt.getApptID()) && eR.getOperation().equals("delete"))
				return eR;
		}
		return null;
	}

	/**
	 * @return cantSched
	 */
	public boolean isCantSched() {
		return cantSched;
	}

	/**
	 * @param cantSched the cantSched to set
	 */
	public void setCantSched(boolean cantSched) {
		this.cantSched = cantSched;
	}
	
	/**
	 * @return badAppts
	 */
	public Set<Appointment> getBadAppts(){
		return badAppts;
	}
	
	/**
	 * resets badAppts
	 */
	public void resetBadAppts(){
		badAppts.clear();
	}


}
