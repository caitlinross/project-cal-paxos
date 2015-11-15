/**
 * @author Caitlin Ross and Erika Mackin
 *
 * Driver to set up a node
 */

import java.io.*;
import java.net.*;
import java.util.*;

public class Driver {
	
	/**
	 * @param args node-setup-file.txt myID totalNodes recovery
	 * cmdline call: Driver node-setup-file.txt myID totalNodes recovery
	 * recovery: 0->new run, otherwise->recovery startup
	 */
	public static void main(String[] args) {
		// set up the network information for all of the nodes
		String filename = args[0]; // node setup file
		int myID = Integer.parseInt(args[1]);
		File file = new File(filename);
		BufferedReader reader = null;
		int totalNodes = Integer.parseInt(args[2]);  
		String[] hostNames = new String[totalNodes];
		boolean recovery;
		if (Integer.parseInt(args[3]) == 0)
			recovery = false;
		else
			recovery = true;
		
		try {
			reader = new BufferedReader(new FileReader(file));
			String text = null;
			int lineNo = 0;
		    while ((text = reader.readLine()) != null) {
		        hostNames[lineNo] = text.trim();
		        lineNo++;
		    }
		    reader.close();
		} catch (FileNotFoundException e2) {
			e2.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	    
		final int port = 4445;

		
		InetAddress inetAddr;
		String hostname = "";
		
		try {
			inetAddr = InetAddress.getLocalHost();
			hostname = inetAddr.getHostName();
		} catch (UnknownHostException e1) {
			e1.printStackTrace();
		}
		
		System.out.println(hostname);
		//set up this node
		final Node node = new Node(totalNodes, port, hostNames, myID, recovery);
		
		// set up this node's serverSocket that continuously listens for other nodes on a new thread
		Runnable listenThread = new Runnable(){
			public synchronized void run() {
				System.out.println("Start listening for other nodes");
				ServerSocket serverSocket;
		        try {
		        	serverSocket = new ServerSocket(port);
		            while (true) {
		            	final Socket client = serverSocket.accept();
		            	Runnable runnable = new Runnable() {
		                    public synchronized void run() {
		                        node.receive(client);
		                    }
		                };
		                new Thread(runnable).start();
		                
		            }
		        } 
		        catch (IOException e) {
					 System.out.println("Exception caught when trying to listen on port " + port);
				    System.out.println(e.getMessage());
					e.printStackTrace();
				}
			}
		};
		new Thread(listenThread).start();
        
		// loop to ask about adding, deleting, viewing appointments
		while(true){
			@SuppressWarnings("resource")
			Scanner in = new Scanner(System.in);
			String action;
			String name;

			System.out.println("Would you like to add or delete an appointment, or print the calendar? (type 'add', 'delete', or 'print')");
			action = in.nextLine().trim();
			if (action.equals("add")) {
				int start;
				int end;
				String sAMPM;
				String eAMPM;
				Day day;
				ArrayList<Integer> participants = new ArrayList<Integer>();
				System.out.println("Please enter an appointment name");
				name = in.nextLine();
				System.out.println("Please enter the appointment day");
				String tmpDay = in.nextLine().toLowerCase();
				if (tmpDay.equals("sunday") || tmpDay.equals("sun"))
					day = Day.SUNDAY;
				else if (tmpDay.equals("monday") || tmpDay.equals("mon"))
					day = Day.MONDAY;
				else if (tmpDay.equals("tuesday") || tmpDay.equals("tues"))
					day = Day.TUESDAY;
				else if (tmpDay.equals("wednesday") || tmpDay.equals("wed"))
					day = Day.WEDNESDAY;
				else if (tmpDay.equals("thursday") || tmpDay.equals("thurs"))
					day = Day.THURSDAY;
				else if (tmpDay.equals("friday") || tmpDay.equals("fri"))
					day = Day.FRIDAY;
				else
					day = Day.SATURDAY;
				System.out.println("Please enter a start time in HHMM format in 30 minute increments");
				start = in.nextInt();
				in.nextLine();
				System.out.println("AM or PM");
				sAMPM = in.nextLine().toUpperCase();
				System.out.println("Please enter an end time in HHMM format in 30 minute increments");
				end = in.nextInt();
				in.nextLine();
				System.out.println("AM or PM");
				eAMPM = in.nextLine().toUpperCase();
				System.out.println("Please enter each participant; enter -1 when done");
				int tmp = in.nextInt();
				while (tmp != -1){
					participants.add(tmp);
					System.out.println("Enter next participant, or -1 if done");
					tmp = in.nextInt();
				}
				
				node.createNewAppointment(participants, name, day, start, end, sAMPM, eAMPM);
			}
			else if (action.equals("delete")) {
				node.printCalendar();
				System.out.println("Please enter the ID number of the appointment (print current appointments to show ID number)");
				String apptId = in.nextLine();
				node.deleteOldAppointment(apptId);
				
			}
			else if (action.equals("print")) {
					node.printCalendar();
			}
			else {
				System.out.println("Action not recognized, please enter 'add', 'delete', or 'print'");
			}
			
			// before asking for next decision, report any appointments that weren't able to be scheduled
			if (node.isCantSched()){
				System.out.println("\n-------- WARNING --------");
				for (Appointment a:node.getBadAppts()){
					System.out.println("Can't schedule appointment ID: " + a.getApptID());
					System.out.println("Name: " + a.getName());
					System.out.println("time: " + a.getStart() + a.getsAMPM() + " - " + a.getEnd() + a.geteAMPM());
					System.out.println("\n");
				}
				node.resetBadAppts();
				node.setCantSched(false);
			}
		}
	}
	
	
}
