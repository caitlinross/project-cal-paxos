/*
 * @author Caitlin Ross and Erika Mackin
 * Enum to identify message types for both TCP and UDP messages
 * 
 * TCP: PROPOSE, CONFLICT
 * UDP: PREPARE, PROMISE, ACCEPT, ACK, COMMIT
 */
public enum MessageType {
	PREPARE, PROMISE, ACCEPT, ACK, COMMIT, PROPOSE, CONFLICT, ELECTION, OK, COORDINATOR, DUMMY
}
