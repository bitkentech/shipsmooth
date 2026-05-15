--------------------------- MODULE ShipSmooth_Milestones ---------------------------
EXTENDS Naturals, Sequences, FiniteSets

CONSTANTS Tasks

VARIABLES 
    ledger,        \* Map: TaskID -> Seq of Milestones
    git_commits,   \* Map: TaskID -> Nat. Note: As of now, this not used in any Invariants. It is purely indicative of liveness
    heartbeat,     \* Transient: Boolean (OS process lock)
    pc             \* Process state: {"running", "crashed"}

vars == <<ledger, git_commits, heartbeat, pc>>

Milestones == {"TASK_CREATED", "DE_RISK_FINISHED", "HARDEN_FINISHED", "TASK_FINISHED"}

-----------------------------------------------------------------------------

Init == 
    /\ ledger = [t \in Tasks |-> << >>]
    /\ git_commits = [t \in Tasks |-> 0]
    /\ heartbeat = [t \in Tasks |-> FALSE]
    /\ pc = "running"

HasMilestone(t, m) == \E i \in 1..Len(ledger[t]) : ledger[t][i] = m

\* The task is in the plan but hasn't reached the terminal state.
IsRegistered(t) == HasMilestone(t, "TASK_CREATED") /\ ~HasMilestone(t, "TASK_FINISHED")

-----------------------------------------------------------------------------

\* Phase 2 Preamble: Add to plan.
RegisterTask(t) ==
    /\ ledger[t] = << >>
    /\ ledger' = [ledger EXCEPT ![t] = Append(@, "TASK_CREATED")]
    /\ UNCHANGED <<heartbeat, git_commits, pc>>

\* Unified Acquisition: If it's registered and the lock is free, take it.
\* This covers both "Start" and "Resume" safely.
AcquireTask(t) ==
    /\ pc = "running"
    /\ ~heartbeat[t]
    /\ IsRegistered(t)
    /\ heartbeat' = [heartbeat EXCEPT ![t] = TRUE]
    /\ UNCHANGED <<ledger, git_commits, pc>>

CommitWork(t) ==
    /\ heartbeat[t]
    /\ git_commits' = [git_commits EXCEPT ![t] = @ + 1]
    /\ UNCHANGED <<ledger, heartbeat, pc>>

FinishDeRisk(t) ==
    /\ heartbeat[t]
    /\ ~HasMilestone(t, "DE_RISK_FINISHED")
    /\ ledger' = [ledger EXCEPT ![t] = Append(@, "DE_RISK_FINISHED")]
    /\ UNCHANGED <<git_commits, heartbeat, pc>>

FinishHarden(t) ==
    /\ heartbeat[t]
    /\ ~HasMilestone(t, "HARDEN_FINISHED")
    /\ ledger' = [ledger EXCEPT ![t] = Append(@, "HARDEN_FINISHED")]
    /\ UNCHANGED <<git_commits, heartbeat, pc>>

FinalizeTask(t) ==
    /\ heartbeat[t]
    /\ ledger' = [ledger EXCEPT ![t] = Append(@, "TASK_FINISHED")]
    /\ heartbeat' = [heartbeat EXCEPT ![t] = FALSE]
    /\ UNCHANGED <<git_commits, pc>>

-----------------------------------------------------------------------------

Crash == 
    /\ pc = "running"
    /\ pc' = "crashed"
    /\ heartbeat' = [t \in Tasks |-> FALSE]
    /\ UNCHANGED <<ledger, git_commits>>

Reconcile(t) ==
    /\ pc = "crashed"
    /\ pc' = "running"
    /\ UNCHANGED <<ledger, git_commits, heartbeat>>

Next == \E t \in Tasks : 
    \/ RegisterTask(t)
    \/ AcquireTask(t)
    \/ CommitWork(t) 
    \/ FinishDeRisk(t)
    \/ FinishHarden(t)
    \/ FinalizeTask(t) 
    \/ Crash
    \/ Reconcile(t)

Spec == Init /\ [][Next]_vars
=============================================================================
