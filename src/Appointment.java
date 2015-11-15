/**
 * @author Caitlin Ross and Erika Mackin
 *
 * Object that contains all info for an appointment
 */

import java.io.Serializable;
import java.util.ArrayList;

@SuppressWarnings("serial")
public class Appointment implements Serializable, Comparable<Appointment> {
	// all fields are serializable
	private String name;
	private Day day;
	private int start;
	private int end;
	private String sAMPM;
	private String eAMPM;
	private ArrayList<Integer> participants;
	private int initNode;
	private String apptID; // unique id for each appointment
	private static int apptNo = 0;
	
	// use these indices in the calendar arrays
	private int startIndex;
	private int endIndex;
	
	// constructor for creating new appointment
	public Appointment(String name, Day day, int start, int end, String sAMPM, String eAMPM, ArrayList<Integer> participants, int initNode) {
		this.name = name;
		this.day = day;
		this.start = start;
		this.end = end;
		this.setsAMPM(sAMPM);
		this.seteAMPM(eAMPM);
		this.participants = participants;
		this.setStartIndex(convertTime(start, sAMPM));
		this.setEndIndex(convertTime(end, eAMPM));
		this.initNode = initNode;
		this.setApptID(Appointment.apptNo, this.initNode);
		Appointment.apptNo++;
	}
	
	// constructor used when creating appointments while restoring node state
	public Appointment(String name, Day day, int start, int end, String sAMPM, String eAMPM, String apptID, ArrayList<Integer> participants, int initNode) {
		this.name = name;
		this.day = day;
		this.start = start;
		this.end = end;
		this.setsAMPM(sAMPM);
		this.seteAMPM(eAMPM);
		this.participants = participants;
		this.setStartIndex(convertTime(start, sAMPM));
		this.setEndIndex(convertTime(end, eAMPM));
		this.apptID = apptID;
	}
	
	public int compareTo(Appointment otherAppt) {
        return this.getStartIndex() - otherAppt.getStartIndex();
    }
	
	public String getName() {
		return this.name;
	}
	
	public void setName(String name) {
		this.name = name;
	}

	public Day getDay() {
		return this.day;
	}

	public void setDay(Day day) {
		this.day = day;
	}

	public int getStart() {
		return this.start;
	}

	public void setStart(int start) {
		this.start = start;
	}

	public int getEnd() {
		return this.end;
	}

	public void setEnd(int end) {
		this.end = end;
	}

	public ArrayList<Integer> getParticipants() {
		return this.participants;
	}

	public void setParticipants(ArrayList<Integer> participants) {
		this.participants = participants;
	}
	
	/**
	 * @return the startIndex
	 */
	public int getStartIndex() {
		return startIndex;
	}

	/**
	 * @param startIndex the startIndex to set
	 */
	public void setStartIndex(int startIndex) {
		this.startIndex = startIndex;
	}

	/**
	 * @return the endIndex
	 */
	public int getEndIndex() {
		return endIndex;
	}

	/**
	 * @param endIndex the endIndex to set
	 */
	public void setEndIndex(int endIndex) {
		this.endIndex = endIndex;
	}

	/**
	 * @return the sAMPM
	 */
	public String getsAMPM() {
		return sAMPM;
	}

	/**
	 * @param sAMPM the sAMPM to set
	 */
	public void setsAMPM(String sAMPM) {
		this.sAMPM = sAMPM;
	}

	/**
	 * @return the eAMPM
	 */
	public String geteAMPM() {
		return eAMPM;
	}

	/**
	 * @param eAMPM the eAMPM to set
	 */
	public void seteAMPM(String eAMPM) {
		this.eAMPM = eAMPM;
	}

	/**
	 * @return the apptID
	 */
	public String getApptID() {
		return apptID;
	}

	/**
	 * @param apptID the apptID to set
	 */
	public void setApptID(int apptID, int initNode) {
		this.apptID = initNode + "_" + apptID;
	}
	
	public static void setApptNo(int apptNo){
		Appointment.apptNo = apptNo;
	}
	
	public static int getApptNo(){
		return Appointment.apptNo;
	}

	/**
	 * converts a time stamp to the appropriate calendar array index
	 * @param time timestamp to convert
	 * @param amPM am or pm?
	 * @return
	 */
	public static int convertTime(int time, String amPM){
		int index = 0;
		if (time == 1200){
			if (amPM.equals("AM"))
				index = 0;
			else
				index = 24;
		}
		else if (time == 1230){
			if (amPM.equals("AM"))
				index = 1;
			else
				index = 25;
		}
		else {
			index = time/100*2;
			if ((time % 100) % 60 == 30)
				index++;
			if (amPM.equals("PM"))
				index += 24;
		}
		return index;
	}
	
}
