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
public class LogEntry implements Serializable {

	private Set<Appointment> appts;
	private int logPos;
	private boolean unknown; 
	/**
	 * 
	 */
	public LogEntry(int logPos) {
		// TODO Auto-generated constructor stub
		this.appts = new HashSet<Appointment>();
		this.setLogPos(logPos);
		this.setUnknown(true);
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
	
	public Set<Appointment> getAppts(){
		return appts;
	}

}
