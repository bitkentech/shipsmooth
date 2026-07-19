use std::path::PathBuf;

/// One typed error surface for the crate — the counterpart of the Java side's
/// checked exceptions (`JAXBException`, `ScaffoldException`,
/// `StateRootUnsettledException`, `InaccessibleRootException`, …).
///
/// Message texts that the cli layer or tests match on must stay identical to
/// the Java originals; each variant's `Display` is part of the port contract.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Xml(String),

    #[error(transparent)]
    Io(#[from] std::io::Error),

    #[error("{0}")]
    Scaffold(String),

    /// Java: StateRootUnsettledException — the resolve gate turns this into
    /// the needs-decision/unresolvable JSON (exit 10/11).
    #[error("shipsmooth state is not set up yet — run `store init` first")]
    StateRootUnsettled,

    /// Java: InaccessibleRootException(role, path, reason).
    #[error("{role} root {path} is not accessible: {reason}")]
    InaccessibleRoot {
        role: String,
        path: PathBuf,
        reason: String,
    },

    /// Java: IllegalArgumentException("Task " + taskId + " not found").
    #[error("Task {0} not found")]
    TaskNotFound(u32),
}

#[cfg(test)]
mod tests {
    use super::*;

    // Display texts are contract: the Java originals, verbatim.
    #[test]
    fn state_root_unsettled_message_matches_java() {
        assert_eq!(
            Error::StateRootUnsettled.to_string(),
            "shipsmooth state is not set up yet — run `store init` first"
        );
    }

    #[test]
    fn task_not_found_message_matches_java() {
        assert_eq!(Error::TaskNotFound(7).to_string(), "Task 7 not found");
    }
}
