@import io.bitken.ss.resources.PluginModel
@param PluginModel model
```tla
--------------------------- MODULE ShipSmooth_Milestones ---------------------------
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS Tasks

VARIABLES 
    ledger,        \* Map: TaskID -> Seq of Milestones
    git_commits,   \* Map: TaskID -> Nat
    heartbeat,     \* Transient: Boolean (OS process lock)
    pc             \* Process state: {"running", "crashed"}

vars == <<ledger, git_commits, heartbeat, pc>>

Milestones == {"TASK_CREATED", "DE_RISK_FINISHED", "HARDEN_FINISHED", "TASK_FINISHED"}

-----------------------------------------------------------------------------

TypeOK == 
    /\ ledger \in [Tasks -> Seq(Milestones)]
    /\ git_commits \in [Tasks -> Nat]
    /\ heartbeat \in [Tasks -> BOOLEAN]
    /\ pc \in {"running", "crashed"}

Init == 
    /\ ledger = [t \in Tasks |-> << >>]
    /\ git_commits = [t \in Tasks |-> 0]
    /\ heartbeat = [t \in Tasks |-> FALSE]
    /\ pc = "running"

HasMilestone(t, m) == \E i \in 1..Len(ledger[t]) : ledger[t][i] = m

\* The task is in the plan but hasn't reached the terminal state.
IsRegistered(t) == HasMilestone(t, "TASK_CREATED") /\ ~HasMilestone(t, "TASK_FINISHED")

-----------------------------------------------------------------------------

\* Phase 2 Preamble: Add to plan. No lock required.
RegisterTask(t) ==
    /\ pc = "running"
    /\ ledger[t] = << >>
    /\ ledger' = [ledger EXCEPT ![t] = Append(${"@"}, "TASK_CREATED")]
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
    /\ pc = "running"
    /\ heartbeat[t]
    /\ git_commits' = [git_commits EXCEPT ![t] = ${"@"} + 1]
    /\ UNCHANGED <<ledger, heartbeat, pc>>

FinishDeRisk(t) ==
    /\ pc = "running"
    /\ heartbeat[t]
    /\ ~HasMilestone(t, "DE_RISK_FINISHED")
    /\ ledger' = [ledger EXCEPT ![t] = Append(${"@"}, "DE_RISK_FINISHED")]
    /\ UNCHANGED <<git_commits, heartbeat, pc>>

FinishHarden(t) ==
    /\ pc = "running"
    /\ heartbeat[t]
    /\ ~HasMilestone(t, "HARDEN_FINISHED")
    /\ ledger' = [ledger EXCEPT ![t] = Append(${"@"}, "HARDEN_FINISHED")]
    /\ UNCHANGED <<git_commits, heartbeat, pc>>

FinalizeTask(t) ==
    /\ pc = "running"
    /\ heartbeat[t]
    /\ ledger' = [ledger EXCEPT ![t] = Append(${"@"}, "TASK_FINISHED")]
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
    \* Ensure the environment is in a valid state relative to the ledger
    /\  \/ ledger[t] = << >>
        \/ IsRegistered(t)
        \/ HasMilestone(t, "TASK_FINISHED")

-----------------------------------------------------------------------------
\* INVARIANTS

\* Audit Trail: Every non-empty task history must start with registration.
LedgerIntegrity == \A t \in Tasks :
    Len(ledger[t]) > 0 => ledger[t][1] = "TASK_CREATED"

\* Lease Safety: Finished tasks must not hold an active lock.
NoZombieHeartbeat ==
    \A t \in Tasks : HasMilestone(t, "TASK_FINISHED") => ~heartbeat[t]

Next == \E t \in Tasks : 
    \/ RegisterTask(t)
    \/ AcquireTask(t)
    \/ CommitWork(t) 
    \/ FinishDeRisk(t)
    \/ FinishHarden(t)
    \/ FinalizeTask(t) 
    \/ Crash
    \/ Reconcile(t)

CommitLimit == \A t \in Tasks : git_commits[t] <= 5
symm == Permutations(Tasks)

Spec == Init /\ [][Next]_vars
=============================================================================
```