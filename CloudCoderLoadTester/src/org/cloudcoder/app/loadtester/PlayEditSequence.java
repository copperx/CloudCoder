package org.cloudcoder.app.loadtester;

import java.util.ArrayList;
import java.util.List;

import org.cloudcoder.app.shared.model.Change;
import org.cloudcoder.app.shared.model.ChangeType;
import org.cloudcoder.app.shared.model.CloudCoderAuthenticationException;
import org.cloudcoder.app.shared.model.CourseAndCourseRegistration;
import org.cloudcoder.app.shared.model.ICallback;
import org.cloudcoder.app.shared.model.Problem;
import org.cloudcoder.app.shared.model.QuizEndedException;
import org.cloudcoder.app.shared.model.SubmissionException;
import org.cloudcoder.app.shared.model.SubmissionResult;

/**
 * Play an {@link EditSequence}.
 * 
 * @author David Hovemeyer
 */
public class PlayEditSequence {
	/**
	 * Default interval for sending batches of {@link Change}s
	 * to the webapp.
	 */
	public static final long SEND_BATCH_INTERVAL_MS = 2000L;
	
	/**
	 * Default interval for polling webapp to check whether a {@link SubmissionResult}
	 * is ready following a code submission.
	 */
	public static final long POLL_SUBMISSION_INTERVAL_MS = 1000L;
	
	private Client client;
	private EditSequence editSequence;
	private Problem problem;
	private long sendBatchIntervalMs;
	private boolean submitOnFullTextChange;
	private long pollSubmissionIntervalMs;
	private ICallback<Change[]> onSend;
	private ICallback<SubmissionResult> onSubmissionResult;
	
	/**
	 * Constructor.
	 */
	public PlayEditSequence() {
		this.sendBatchIntervalMs = SEND_BATCH_INTERVAL_MS;
		this.submitOnFullTextChange = true;
		this.pollSubmissionIntervalMs = POLL_SUBMISSION_INTERVAL_MS;
	}
	
	/**
	 * Set the {@link Client} to use to communicate with the webapp.
	 * It should already be logged in.
	 * 
	 * @param client the {@link Client}
	 */
	public void setClient(Client client) {
		this.client = client;
	}

	/**
	 * Set the {@link EditSequence} to play.
	 * 
	 * @param editSequence the {@link EditSequence}
	 */
	public void setEditSequence(EditSequence editSequence) {
		this.editSequence = editSequence;
	}
	
	/**
	 * Set the interval (in milliseconds) at which batches of {@link Change}s should
	 * be sent.  Defaults to {@link #SEND_BATCH_INTERVAL_MS}.
	 * 
	 * @param sendBatchIntervalMs interval at which batches of {@link Change}s should be
	 *                            sent, in milliseconds
	 */
	public void setSendBatchIntervalMs(long sendBatchIntervalMs) {
		this.sendBatchIntervalMs = sendBatchIntervalMs;
	}
	
	/**
	 * Set whether full text changes (other than the initial one that
	 * sets the skeleton text) should be treated as submissions.
	 * Defaults to true, since that is the most realistic behavior:
	 * the webapp client-side javascript code issues a full text change
	 * when the user submits.
	 * 
	 * @param submitOnFullTextChange true if full text changes should result in
	 *                               a submission
	 */
	public void setSubmitOnFullTextChange(boolean submitOnFullTextChange) {
		this.submitOnFullTextChange = submitOnFullTextChange;
	}
	
	/**
	 * Set the interval at which the client should poll for a
	 * {@link SubmissionResult} following a code submission.
	 * Defaults to {@link #POLL_SUBMISSION_INTERVAL_MS}.
	 * 
	 * @param pollSubmissionIntervalMs interval at which the client should poll for a
	 *                                 {@link SubmissionResult} following a code submission
	 */
	public void setPollSubmissionIntervalMs(long pollSubmissionIntervalMs) {
		this.pollSubmissionIntervalMs = pollSubmissionIntervalMs;
	}
	
	/**
	 * Set callback to be invoked when {@link Change}s are sent.
	 * 
	 * @param onSend callback when {@link Change}s are sent
	 */
	public void setOnSend(ICallback<Change[]> onSend) {
		this.onSend = onSend;
	}
	
	/**
	 * Set callback to be invoked when a {@link SubmissionResult} is received.
	 * 
	 * @param onSubmissionResult callback when a {@link SubmissionResult} is received
	 */
	public void setOnSubmissionResult(ICallback<SubmissionResult> onSubmissionResult) {
		this.onSubmissionResult = onSubmissionResult;
	}

	/**
	 * Prepare to play the {@link EditSequence}.
	 * This should be called once, before the first call to {@link #play()}.
	 * 
	 * @throws CloudCoderAuthenticationException
	 */
	public void setup() throws CloudCoderAuthenticationException {
		// Fix up all of the Change objects by resetting the event id
		// to the default (0) value and changing the user id to that of the
		// logged-in user.
		List<Change> changeList = editSequence.getChangeList();
		for (Change change : changeList) {
			change.getEvent().setId(0);
			change.setEventId(0);
			change.getEvent().setUserId(client.getUser().getId());
		}
		
		// Find the Problem (exercise)
		CourseAndCourseRegistration[] courses = client.getRegisteredCourses();
		for (CourseAndCourseRegistration c : courses) {
			Problem[] problems = client.getProblemsForCourse(c.getCourse());
			for (Problem p : problems) {
				if (p.getTestname().equals(editSequence.getExerciseName())) {
					this.problem = p;
				}
			}
		}
		if (this.problem == null) {
			throw new RuntimeException("Could not find exercise " + editSequence.getExerciseName());
		}
		
		// Set the Problem
		client.setProblem(problem);
	}
	
	/**
	 * Play the {@link EditSequence}, attempting to emulate the
	 * relative timing and batching of the original Change events.
	 * This should be called after calling {@link #setup()},
	 * and can be called any number of times.
	 * 
	 * @param onSend callback to invoke when Changes are sent
	 * @throws CloudCoderAuthenticationException
	 * @throws InterruptedException 
	 * @throws QuizEndedException 
	 * @throws SubmissionException 
	 */
	public void play() throws CloudCoderAuthenticationException, InterruptedException, QuizEndedException, SubmissionException {
		boolean done = false;
		
		// Schedule the first "timer" event where a batch of Changes
		// can be sent
		long nextSend = System.currentTimeMillis() + sendBatchIntervalMs;
		
		// Use the timestamp of the first Change in the EditSequence
		// to set the initial window for sending Changes
		long windowStart = editSequence.getChangeList().get(0).getEvent().getTimestamp();
		
		while (!done) {
			// Get all Changes to be sent in the next batch.
			// This will update the 
			List<Change> batch = new ArrayList<Change>();
			windowStart = createBatch(windowStart, batch);

			if (windowStart < 0L) {
				// All Changes have been sent, so we're done
				done = true;
			} else {
				// Wait until next time to send.
				// We're basically emulating the repeating timer that the
				// webapp's client-side javascript code uses to flush
				// accumulated Changes periodically.
				long delay = nextSend - System.currentTimeMillis();
				if (delay > 0L) {
					Thread.sleep(delay);
				}

				// If there is a batch of changes to send, send them
				if (batch.size() > 0) {
					// Send the batch of Changes
					Change[] arr = batch.toArray(new Change[batch.size()]);
					if (onSend != null) {
						onSend.call(arr);
					}
					client.sendChanges(arr);

					// Special case: if full text-changes are treated as submissions,
					// and this batch is a single full-text change, then submit the code
					if (submitOnFullTextChange && batch.get(0).getType() == ChangeType.FULL_TEXT) {
						SubmissionResult submissionResult = client.submitCode(
								problem.getProblemId(),
								batch.get(0).getText(),
								pollSubmissionIntervalMs);
						if (onSubmissionResult != null) {
							onSubmissionResult.call(submissionResult);
						}
					}
				}

				// Schedule the next send time
				nextSend += sendBatchIntervalMs;
				long now = System.currentTimeMillis();
				if (now > nextSend) {
					// Sending the changes took more time than the send interval,
					// so schedule the next send to happen immediately.
					nextSend = now + 1; 
				}
			}
		}
	}

	/**
	 * Create a batch of {@link Change}s, containing all changes
	 * within {@link #sendBatchIntervalMs} of the given window start
	 * time.
	 * 
	 * @param windowStart the window start time
	 * @param batch       a list to which {@link Change}s in the current
	 *                    window should be added
	 * @return the start time for the next window, or -1L if there are no more
	 *         changes in the {@link EditSequence} to send
	 */
	private long createBatch(long windowStart, List<Change> batch) {
		// Assume that all changes have been sent
		boolean allChangesSent = true;

		// Next window start timestamp.
		long nextWindowStart = windowStart + sendBatchIntervalMs;
		
		// Find Changes that are within the current window to add
		// to the batch.  It is possible that there are none.
		// Also, check to see if we can find any Changes
		// with a timestamp greater than the current window start.
		// If there aren't any, then we know that all Changes
		// have been sent.
		for (Change c : editSequence.getChangeList()) {
			if (c.getEvent().getTimestamp() >= windowStart) {
				// There is at least one Change with a timestamp greater than
				// the window start timestamp, so we haven't yet sent all of
				// the Changes in the EditSequence
				allChangesSent = false;
				
				if (c.getEvent().getTimestamp() >= windowStart + sendBatchIntervalMs) {
					// Reached the end of the window
					break;
				}
				
				// Special case: if full text changes are being treated as
				// submissions, then a full text change should always be
				// isolated into a single-change batch.  In other words,
				// each batch should contain either normal (delta) changes,
				// or a single full text change, or nothing.
				if (submitOnFullTextChange && c.getType() == ChangeType.FULL_TEXT) {
					// Found a full text submission in the window
					if (batch.isEmpty()) {
						// This will be the only change in the batch
						batch.add(c);
						nextWindowStart = c.getEvent().getTimestamp() + 1;
					} else {
						// Batch already contains at least one normal (delta) change.
						// Next window should start with just this full-text change.
						nextWindowStart = c.getEvent().getTimestamp();
					}
					break;
				}

				// Normal (delta) change within the current window, so add it to
				// the batch
				batch.add(c);
			}
		}
		
		// Return the start time for the next window,
		// or -1L if all of the Changes in the EditSequence have
		// been sent
		if (allChangesSent) {
			nextWindowStart = -1L;
		}
		return nextWindowStart;
	}
}
