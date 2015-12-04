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
	/**
	 * @param logPosition the position in the log for this entry
	 */
	public LogEntry(int logPos, int owner) {
		this.appts = new HashSet<Appointment>();
		this.setLogPos(logPos);
		this.setUnknown(true);
		this.setOwner(owner);
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
	
	

}
