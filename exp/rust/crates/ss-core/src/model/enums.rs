//! Typed views over the XSD enumerations. The model stores every value as the
//! lexical string from the file (leniency parity with JAXB unmarshalling —
//! see plan-tasks.xsd); these types give callers validated views on demand.

use std::fmt;
use std::str::FromStr;

use crate::Error;

macro_rules! xsd_enum {
    ($(#[$doc:meta])* $name:ident, $label:literal, { $($variant:ident => $token:literal),+ $(,)? }) => {
        $(#[$doc])*
        #[derive(Clone, Copy, Debug, PartialEq, Eq)]
        pub enum $name {
            $($variant),+
        }

        impl $name {
            pub fn as_str(self) -> &'static str {
                match self {
                    $($name::$variant => $token),+
                }
            }

            /// Every variant, in XSD declaration order — for allowed-values
            /// messages (e.g. an invalid `--status` on the CLI).
            pub const ALL: &'static [$name] = &[$($name::$variant),+];
        }

        impl FromStr for $name {
            type Err = Error;

            fn from_str(s: &str) -> Result<Self, Error> {
                match s {
                    $($token => Ok($name::$variant),)+
                    other => Err(Error::Xml(format!("invalid {} '{other}'", $label))),
                }
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(self.as_str())
            }
        }
    };
}

xsd_enum!(
    /// `PlanStatusType`.
    PlanStatus, "plan status", {
        Active => "active",
        Complete => "complete",
        Abandoned => "abandoned",
        InReview => "in-review",
    }
);

xsd_enum!(
    /// `TaskStatusType`.
    TaskStatus, "task status", {
        Pending => "pending",
        InProgress => "in-progress",
        DeRisked => "de-risked",
        AgentCoded => "agent-coded",
        Closed => "closed",
        NeedsTriage => "needs-triage",
        Abandoned => "abandoned",
    }
);

xsd_enum!(
    /// `RiskLevelType` — the XSD allows the empty string for "unspecified".
    Risk, "risk level", {
        High => "high",
        Medium => "medium",
        Low => "low",
        Unspecified => "",
    }
);

xsd_enum!(
    /// `DeviationTypeEnum`.
    DeviationKind, "deviation type", {
        Minor => "minor",
        Major => "major",
    }
);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_round_trip_through_parse_and_display() {
        for status in ["pending", "in-progress", "de-risked", "agent-coded", "closed", "needs-triage", "abandoned"] {
            assert_eq!(status.parse::<TaskStatus>().unwrap().to_string(), status);
        }
        for status in ["active", "complete", "abandoned", "in-review"] {
            assert_eq!(status.parse::<PlanStatus>().unwrap().to_string(), status);
        }
        for kind in ["minor", "major"] {
            assert_eq!(kind.parse::<DeviationKind>().unwrap().to_string(), kind);
        }
        assert_eq!("".parse::<Risk>().unwrap(), Risk::Unspecified);
        assert_eq!(Risk::Unspecified.to_string(), "");
    }

    #[test]
    fn invalid_token_names_the_kind_and_value() {
        let err = "bogus".parse::<TaskStatus>().unwrap_err();
        assert_eq!(err.to_string(), "invalid task status 'bogus'");
    }

    #[test]
    fn all_lists_every_variant_in_xsd_declaration_order() {
        let tokens: Vec<&str> = TaskStatus::ALL.iter().map(|s| s.as_str()).collect();
        assert_eq!(
            tokens,
            vec!["pending", "in-progress", "de-risked", "agent-coded", "closed", "needs-triage", "abandoned"]
        );
    }
}
