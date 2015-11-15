/**
 * @author Caitlin Ross and Erika Mackin
 *
 * Object that stores information about an event
 */

import java.io.Serializable;

@SuppressWarnings("serial")
public class EventRecord implements Serializable{
	/**
	 * @param operation Either insert or delete operation
	 * @param time The timestamp appointment was created (based on creator's clock)
	 * @param nodeId The creator of appointment
	 * @param Appointment for this event
	 */
	private String operation;
	private int time;
	private int nodeId;
	private Appointment appointment;
	
	/**
	 * constructor to be used when creating event
	 */
	public EventRecord(String operation, int time, int nodeId) {
		this.setOperation(operation);
		this.setTime(time);
		this.setNodeId(nodeId);
	}
	
	/**
	 *  constructor only to be used when creating an event while restoring a crashed node's state
	 *  i.e. these events already existed but only need to be restored
	 */
	public EventRecord(String operation, int time, int nodeId, Appointment appointment) {
		this.setOperation(operation);
		this.setTime(time);
		this.setNodeId(nodeId);
		this.setAppointment(appointment);
	}

	/**
	 * @return the operation
	 */
	public String getOperation() {
		return operation;
	}

	/**
	 * @param operation the operation to set
	 */
	public void setOperation(String operation) {
		this.operation = operation;
	}

	/**
	 * @return the time
	 */
	public int getTime() {
		return time;
	}

	/**
	 * @param time the time to set
	 */
	public void setTime(int time) {
		this.time = time;
	}

	/**
	 * @return the nodeId
	 */
	public int getNodeId() {
		return nodeId;
	}

	/**
	 * @param nodeId the nodeId to set
	 */
	public void setNodeId(int nodeId) {
		this.nodeId = nodeId;
	}

	/**
	 * @return the appointment
	 */
	public Appointment getAppointment() {
		return appointment;
	}

	/**
	 * @param appointment the appointment to set
	 */
	public void setAppointment(Appointment appointment) {
		this.appointment = appointment;
	}
	
	

}
