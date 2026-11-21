//! Behaviour of `tpp notification list`, independent of any former script:
//! what is printed, how the app's failures are reported, and that help and bad
//! arguments never reach the app.

use tpp_api::testing::{run_fake, run_with, Answer, FakeTransport};

const ACCESS_ERROR: &str = "Notification access is not granted to TermuxPP. Enable it in the \
Android settings (Notification access), then run the command again.";

fn access_error_reply() -> String {
    format!("{{\n  \"API_ERROR\": \"{ACCESS_ERROR}\"\n}}\n")
}

#[test]
fn notifications_are_printed_and_the_app_is_asked_once() {
    let reply = "[\n  {\n    \"id\": 1,\n    \"content\": \"API_ERROR\"\n  }\n]\n";
    let (out, calls) = run_fake("notification-list", &[], b"", reply);
    assert_eq!(out.status, 0);
    assert_eq!(out.stdout_str(), reply);
    assert!(out.stdout_str().starts_with("[\n"));
    assert_eq!(out.stderr_str(), "");
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].method, "NotificationList");
    assert!(calls[0].args.is_empty());
}

#[test]
fn an_empty_list_is_a_success() {
    let (out, _) = run_fake("notification-list", &[], b"", "[]\n");
    assert_eq!((out.status, out.stdout_str()), (0, "[]\n".to_string()));
    assert_eq!(out.stderr_str(), "");
}

#[test]
fn missing_notification_access_fails_with_the_message_and_no_stdout() {
    let (out, _) = run_fake("notification-list", &[], b"", &access_error_reply());
    assert_eq!(out.status, 1);
    assert_eq!(out.stdout_str(), "");
    assert_eq!(
        out.stderr_str(),
        format!("tpp notification list: {ACCESS_ERROR}\n")
    );
}

#[test]
fn an_object_without_a_message_still_fails() {
    let (out, _) = run_fake("notification-list", &[], b"", "{\n  \"x\": 1\n}\n");
    assert_eq!(out.status, 1);
    assert_eq!(out.stdout_str(), "");
    assert_eq!(
        out.stderr_str(),
        "tpp notification list: failed to list the notifications\n"
    );
}

#[test]
fn an_empty_response_fails() {
    for reply in ["", "\n"] {
        let (out, _) = run_fake("notification-list", &[], b"", reply);
        assert_eq!(out.status, 1, "{reply:?}");
        assert_eq!(out.stdout_str(), "");
        assert!(out.stderr_str().contains("no response"), "{reply:?}");
    }
}

#[test]
fn a_failing_transport_is_an_error_without_stdout() {
    let t = FakeTransport::with_answers(vec![Answer {
        error: Some("boom".into()),
        ..Default::default()
    }]);
    let out = run_with("notification-list", &[], b"", &t, &[]);
    assert_eq!(out.status, 1);
    assert_eq!(out.stdout_str(), "");
    assert_eq!(out.stderr_str(), "tpp: boom\n");
    assert_eq!(t.calls().len(), 1);
}

#[test]
fn help_and_bad_arguments_do_not_call_the_app() {
    let t = FakeTransport::replying("[]\n");
    let out = run_with("notification-list", &["-h"], b"", &t, &[]);
    assert_eq!((out.status, out.stderr_str()), (0, String::new()));
    assert!(out.stdout_str().contains("Usage:"), "{}", out.stdout_str());
    let out = run_with("notification-list", &["extra"], b"", &t, &[]);
    assert_eq!(out.status, 1);
    assert!(
        format!("{}{}", out.stdout_str(), out.stderr_str()).contains("too many arguments"),
        "{} / {}",
        out.stdout_str(),
        out.stderr_str()
    );
    assert!(t.calls().is_empty());
}
