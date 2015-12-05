import java.io.*;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Caitlin Ross and Erika Mackin
 *
 * This object stores details for a given log entry.
 * One log entry is a full calendar (As opposed to an individual event)
 * 
 * TODO add more functionality as necessary?
 */
@SuppressWarnings("serial")
public class LogEntry implements Serializable, Comparable<LogEntry> {

	private Set<Appointment> appts;
	private int logPos;
	private boolean unknown; 
	private int owner;
	private int calendar[][][];

	/**
	 * @param logPosition the position in the log for this entry
	 */
	public LogEntry(int logPos, int owner) {
		this.appts = new HashSet<Appointment>();
		this.calendar = new int[5][7][48];
		this.setLogPos(logPos);
		this.setUnknown(true);
		this.setOwner(owner);
	}
	
	/**
	 * @return the appts list
	 */
	public Set<Appointment> getAppts() {
		return appts;
	}
	
	/**
	 * @param appts the appts to set
	 */
	public void setAppts(Set<Appointment> appts) {
		this.appts = appts;
	}
	
	/**
	 * @return the calendar
	 */
	public int[][][] getCalendar() {
		return calendar;
	}
	
	/**
	 * @param calendar the calendar to set
	 */
	public void setCalendar(int[][][] calendar) {
		this.calendar = calendar;
	}
	/**
	 * @return the logPos
	 */
	public int getLogPos() {
		return logPos;
	}
	/**
	 * @param logPos the logPos to set
	 */
	public void setLogPos(int logPos) {
		this.logPos = logPos;
	}

	/**
	 * @return the unknown
	 */
	public boolean isUnknown() {
		return unknown;
	}
	/**
	 * @param unknown the unknown to set
	 */
	public void setUnknown(boolean unknown) {
		this.unknown = unknown;
	}
	
	public void addAppt(Appointment appt){
		this.appts.add(appt);
	}
	
	
	@Override
	public int compareTo(LogEntry o) {
		if (this.logPos == o.logPos)
			return 0;
		else if (this.logPos > o.logPos)
			return 1;
		else
			return -1;
	}
	/**
	 * @return the owner
	 */
	public int getOwner() {
		return owner;
	}
	/**
	 * @param owner the owner to set
	 */
	public void setOwner(int owner) {
		this.owner = owner;
	}
	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		String str = "LogEntry [logPos=" + logPos + ", unknown=" + unknown + ", owner=" + owner + "]\n";
		for (Appointment a:this.appts){
			str += a.toString() + "\n";
		}
		return str;
	}
	
	/**
	 * Takes a string and converts the information to LogEntry object
	 * @return
	 */
	public static LogEntry fromString(String str){
		LogEntry e = null;
		int logPos = -1;
		boolean unknown = true;
		int owner = -1;
		
		String newStr = str.split("[\\[\\]]")[1]; 
		String[] parts = newStr.split(",");
		for (String s:parts){
			String[] p = s.split("=");
			if (p[0].trim().equals("logPos"))
				logPos = Integer.parseInt(p[1]);
			else if (p[0].trim().equals("unknown"))
				unknown = Boolean.parseBoolean(p[1]);
			else if (p[0].trim().equals("owner"))
				owner = Integer.parseInt(p[1]);
				
		}
		e = new LogEntry(logPos, owner);
		e.setUnknown(unknown);
		
		return e;
	}
	
}
