package io.bitken.ss.gw;

import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.jaxb.*;
import io.bitken.ss.svc.plan.PlanMarkdown;
import io.bitken.ss.svc.plan.PlanMarkdownParser;
import io.bitken.ss.svc.plan.PlanSummaryFormatter;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;

public class TaskStore {

    private final ShipsmoothDataLocator locator;
    private final ObjectFactory factory;
    private final JAXBContext jaxbContext;

    public record Task(int id, String name, String risk, String dependsOn) {
        public Task(int id, String name, String risk) { this(id, name, risk, ""); }
    }

    public TaskStore(ShipsmoothDataLocator locator) {
        this.locator = locator;
        this.factory = new ObjectFactory();
        try {
            this.jaxbContext = JAXBContext.newInstance(PlanTasks.class);
        } catch (JAXBException e) {
            throw new RuntimeException(e);
        }
    }

    private File planTasksFile(int planId) {
        return locator.planTasksFile(planId);
    }

    private File planMarkdownFile(int planId) {
        return locator.planMarkdownFile(planId);
    }

    /** Convenience: load the plan's XML by plan id using the canonical layout. */
    public PlanTasks loadPlan(int planId) throws JAXBException {
        return readPlanTasks(planTasksFile(planId));
    }

    /** Convenience: save the plan's XML by plan id using the canonical layout. */
    public void savePlan(int planId, PlanTasks plan) throws JAXBException {
        writePlanTasks(plan, planTasksFile(planId));
    }

    /** Returns the task's display name, or the id stringified if not present. */
    public String getTaskName(PlanTasks planTasks, int taskId) {
        return planTasks.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == taskId)
                .map(t -> t.getName() != null ? t.getName() : String.valueOf(taskId))
                .findFirst().orElse(String.valueOf(taskId));
    }

    /** Parses "1,2,3" → [1,2,3]. Empty/null → empty list. Malformed entries are skipped with a debug log. */
    public List<Integer> parseDependsOn(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<Integer> result = new ArrayList<>();
        for (String part : s.split(",")) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                System.err.println("parseDependsOn: skipping malformed entry '" + part.trim() + "' in depends-on: " + s);
            }
        }
        return result;
    }

    /** @see PlanMarkdown#sliceTaskSection(int, int) */
    public String sliceTaskMarkdown(int planId, int taskId) {
        return new PlanMarkdown(locator).sliceTaskSection(planId, taskId);
    }

    // Retry papers over a race with concurrent writers: writePlanTasks uses
    // ATOMIC_MOVE, but readers can still race the rename and observe an empty
    // file briefly. Sleep+retry is cheap because the failure mode is rare.
    public PlanTasks readPlanTasks(File file) throws JAXBException {
        int retries = 5;
        while (retries > 0) {
            try {
                Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
                return (PlanTasks) unmarshaller.unmarshal(file);
            } catch (JAXBException e) {
                retries--;
                if (retries == 0) throw e;
                try {
                    //noinspection BusyWait
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new JAXBException("Failed to read XML after retries: " + file.getAbsolutePath());
    }

    public void writePlanTasks(PlanTasks planTasks, File file) throws JAXBException {
        file.getParentFile().mkdirs();
        File tempFile = new File(file.getAbsolutePath() + ".tmp");
        Marshaller marshaller = jaxbContext.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(planTasks, tempFile);
        try {
            java.nio.file.Files.move(tempFile.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            throw new JAXBException("Failed to move temp XML file: " + e.getMessage(), e);
        } finally {
            if (tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    /** @deprecated call {@link PlanMarkdownParser#parse} directly — markdown parsing is not XML I/O. */
    @Deprecated
    public List<Task> parseTasksFromPlan(String markdown) {
        return new PlanMarkdownParser().parse(markdown);
    }

    public PlanTasks generatePlanTasks(int planNum, String planVersion, List<Task> tasks) throws Exception {
        PlanTasks planTasks = factory.createPlanTasks();
        planTasks.setPlan(BigInteger.valueOf(planNum));
        planTasks.setPlanVersion(planVersion);

        MetadataType metadata = factory.createMetadataType();
        metadata.setBacklogIssue("");
        metadata.setStatus(PlanStatusType.ACTIVE);
        metadata.setCreated(getXmlDate(LocalDate.now()));
        planTasks.setMetadata(metadata);

        TasksContainerType tasksContainer = factory.createTasksContainerType();
        for (Task t : tasks) {
            TaskType taskType = factory.createTaskType();
            taskType.setId(BigInteger.valueOf(t.id()));
            taskType.setName(t.name());
            taskType.setRisk(t.risk());
            taskType.setStatus(TaskStatusType.PENDING);
            taskType.setCommit("");
            taskType.setCreatedFrom(planVersion);
            taskType.setClosedAtVersion("");
            taskType.setComments(factory.createCommentsContainerType());
            taskType.setDeviations(factory.createDeviationsContainerType());
            tasksContainer.getTask().add(taskType);
        }
        planTasks.setTasks(tasksContainer);
        for (Task t : tasks) {
            if (t.dependsOn() != null && !t.dependsOn().isBlank()) {
                setDependsOn(planTasks, t.id(), t.dependsOn());
            }
        }

        ProjectUpdatesContainerType updatesContainer = factory.createProjectUpdatesContainerType();
        UpdateType update = factory.createUpdateType();
        update.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        update.setMessage("Plan initialised.");
        update.setBlocked(false);
        updatesContainer.getUpdate().add(update);
        planTasks.setProjectUpdates(updatesContainer);

        return planTasks;
    }

    /**
     * Appends a new task to an existing plan. The id on {@code task} is ignored;
     * the next id is computed as {@code max(existing ids) + 1} (or 1 if empty).
     * The new task starts {@code pending} with an empty commit, {@code createdFrom}
     * set to {@code planVersion}, and a {@code depends-on} element when
     * {@code task.dependsOn()} is non-blank. Returns the assigned id.
     */
    public int addTask(PlanTasks planTasks, Task task, String planVersion) throws Exception {
        int nextId = planTasks.getTasks().getTask().stream()
                .mapToInt(t -> t.getId().intValue())
                .max().orElse(0) + 1;

        TaskType taskType = factory.createTaskType();
        taskType.setId(BigInteger.valueOf(nextId));
        taskType.setName(task.name());
        taskType.setRisk(task.risk());
        taskType.setStatus(TaskStatusType.PENDING);
        taskType.setCommit("");
        taskType.setCreatedFrom(planVersion);
        taskType.setClosedAtVersion("");
        taskType.setComments(factory.createCommentsContainerType());
        taskType.setDeviations(factory.createDeviationsContainerType());
        planTasks.getTasks().getTask().add(taskType);

        if (task.dependsOn() != null && !task.dependsOn().isBlank()) {
            setDependsOn(planTasks, nextId, task.dependsOn());
        }
        return nextId;
    }

    public void updateTaskStatus(PlanTasks planTasks, int taskId, String status) {
        TaskType task = findTask(planTasks, taskId);
        task.setStatus(TaskStatusType.fromValue(status));
    }

    public void addComment(PlanTasks planTasks, int taskId, String message) throws DatatypeConfigurationException {
        TaskType task = findTask(planTasks, taskId);
        CommentType comment = factory.createCommentType();
        comment.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        comment.setMessage(message);
        task.getComments().getComment().add(comment);
    }

    public void addDeviation(PlanTasks planTasks, int taskId, String type, String message) throws DatatypeConfigurationException {
        TaskType task = findTask(planTasks, taskId);
        DeviationType deviation = factory.createDeviationType();
        deviation.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        deviation.setType(DeviationTypeEnum.fromValue(type));
        deviation.setMessage(message);
        task.getDeviations().getDeviation().add(deviation);
    }

    public void setCommit(PlanTasks planTasks, int taskId, String commit) {
        TaskType task = findTask(planTasks, taskId);
        task.setCommit(commit);
    }

    /** Returns the raw <depends-on> string, or "" if absent. */
    public String getDependsOn(PlanTasks planTasks, int taskId) {
        TaskType task = findTask(planTasks, taskId);
        for (Object obj : task.getAny()) {
            if (obj instanceof Element el && "depends-on".equals(el.getLocalName())) {
                String text = el.getTextContent();
                return text != null ? text.trim() : "";
            }
        }
        return "";
    }

    /** Sets or replaces the <depends-on> element on the task. Pass "" to remove it. */
    public void setDependsOn(PlanTasks planTasks, int taskId, String value) throws Exception {
        TaskType task = findTask(planTasks, taskId);
        task.getAny().removeIf(obj -> obj instanceof Element el && "depends-on".equals(el.getLocalName()));
        if (value != null && !value.isBlank()) {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element el = doc.createElement("depends-on");
            el.setTextContent(value.trim());
            task.getAny().add(el);
        }
    }

    public void projectUpdate(PlanTasks planTasks, String status, Boolean blocked, String message) throws DatatypeConfigurationException {
        if (status != null) {
            planTasks.getMetadata().setStatus(PlanStatusType.fromValue(status));
        }
        UpdateType update = factory.createUpdateType();
        update.setTimestamp(getXmlDateTime(OffsetDateTime.now()));
        update.setMessage(message != null ? message : "");
        update.setBlocked(blocked != null ? blocked : false);
        planTasks.getProjectUpdates().getUpdate().add(update);
    }

    /** @see PlanSummaryFormatter#format(PlanTasks) */
    public String formatPlanSummary(PlanTasks planTasks) {
        return new PlanSummaryFormatter().format(planTasks);
    }

    private TaskType findTask(PlanTasks planTasks, int taskId) {
        return planTasks.getTasks().getTask().stream()
                .filter(t -> t.getId().intValue() == taskId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Task " + taskId + " not found"));
    }

    private XMLGregorianCalendar getXmlDate(LocalDate date) throws DatatypeConfigurationException {
        return DatatypeFactory.newInstance().newXMLGregorianCalendar(date.toString());
    }

    private XMLGregorianCalendar getXmlDateTime(OffsetDateTime dateTime) throws DatatypeConfigurationException {
        return DatatypeFactory.newInstance().newXMLGregorianCalendar(GregorianCalendar.from(dateTime.toZonedDateTime()));
    }
}
