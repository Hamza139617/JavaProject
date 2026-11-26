package osl.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class WorkspaceTheme {
    private WorkspaceTheme() {}

    // An inline stylesheet also works when the application is launched from a plain javac output directory.
    static final String STYLESHEET = "data:text/css;base64," + Base64.getEncoder().encodeToString("""
            .workspace-scroll, .workspace-scroll > .viewport {
                -fx-background-color: #edf3ef;
                -fx-background: #edf3ef;
            }
            .dashboard-topbar {
                -fx-background-color: #ffffff;
                -fx-border-color: transparent transparent #dbe6de transparent;
            }
            .dashboard-masthead { -fx-padding: 11 26 11 26; }
            .dashboard-mark {
                -fx-background-color: #153e35;
                -fx-background-radius: 8;
                -fx-alignment: center;
                -fx-padding: 7 9 7 9;
                -fx-text-fill: #dcf5e8;
                -fx-font-size: 12px;
                -fx-font-weight: bold;
            }
            .dashboard-brand { -fx-text-fill: #19362e; -fx-font-weight: bold; -fx-font-size: 14px; }
            .dashboard-page { -fx-padding: 22 26 28 26; }
            .dashboard-hero {
                -fx-background-color: linear-gradient(to right, #123d33, #205843);
                -fx-background-radius: 15;
                -fx-padding: 22 26 23 26;
                -fx-spacing: 18;
            }
            .dashboard-context { -fx-text-fill: #b7dec8; -fx-font-size: 13px; -fx-font-weight: bold; }
            .dashboard-title { -fx-text-fill: #fbfffc; -fx-font-size: 32px; -fx-font-weight: bold; }
            .dashboard-card, .module-surface {
                -fx-background-color: #ffffff;
                -fx-background-radius: 13;
                -fx-border-color: #dbe6de;
                -fx-border-radius: 13;
            }
            .dashboard-card { -fx-padding: 15 16 16 16; }
            .dashboard-section-title { -fx-text-fill: #19362e; -fx-font-size: 16px; -fx-font-weight: bold; }
            .dashboard-field-name, .dashboard-alert-detail { -fx-text-fill: #52685d; -fx-font-size: 12px; }
            .dashboard-field-value { -fx-text-fill: #19362e; -fx-font-size: 14px; -fx-font-weight: bold; }
            .dashboard-feedback {
                -fx-background-color: #e1f2e8;
                -fx-background-radius: 8;
                -fx-padding: 8 12;
                -fx-text-fill: #215541;
            }
            .dashboard-primary, .dashboard-signout, .dashboard-task, .dashboard-edit, .dashboard-alert-link {
                -fx-background-insets: 0;
                -fx-background-radius: 8;
                -fx-border-radius: 8;
                -fx-cursor: hand;
                -fx-font-weight: bold;
            }
            .dashboard-primary {
                -fx-background-color: #e0f4e8;
                -fx-padding: 11 19;
                -fx-text-fill: #153e35;
            }
            .dashboard-primary:hover { -fx-background-color: #ffffff; }
            .dashboard-signout, .dashboard-edit {
                -fx-background-color: #edf4ef;
                -fx-text-fill: #24513f;
                -fx-padding: 7 11;
            }
            .dashboard-task {
                -fx-alignment: center-left;
                -fx-background-color: #f4f8f5;
                -fx-border-color: #e0eae3;
                -fx-text-fill: #234737;
                -fx-padding: 9 12;
            }
            .dashboard-alert-row {
                -fx-background-color: #f4f8f5;
                -fx-background-radius: 9;
                -fx-padding: 8 10;
            }
            .dashboard-alert-link {
                -fx-background-color: transparent;
                -fx-text-fill: #24513f;
                -fx-padding: 2 4 2 0;
            }
            .dashboard-alert-link:hover { -fx-underline: true; }
            .dashboard-count {
                -fx-background-color: #d9efe2;
                -fx-background-radius: 12;
                -fx-padding: 2 8;
                -fx-text-fill: #1c573e;
                -fx-font-weight: bold;
                -fx-alignment: center;
            }
            .dashboard-task:hover, .dashboard-signout:hover, .dashboard-edit:hover { -fx-background-color: #deefe4; }
            .dashboard-primary:focused, .dashboard-signout:focused, .dashboard-task:focused,
            .dashboard-edit:focused, .dashboard-alert-link:focused {
                -fx-border-color: #4a896e;
                -fx-border-width: 1;
            }
            .module-frame { -fx-padding: 18 22 22 22; }
            .module-surface {
                -fx-focus-color: #4a896e;
                -fx-faint-focus-color: #d9efe2;
                -fx-accent: #27634d;
            }
            .module-surface .table-view, .module-surface .list-view {
                -fx-background-color: #ffffff;
                -fx-background-radius: 8;
                -fx-border-color: #dbe6de;
                -fx-border-radius: 8;
                -fx-padding: 1;
            }
            .module-surface .table-view .column-header-background {
                -fx-background-color: #edf4ef;
                -fx-background-radius: 7 7 0 0;
            }
            .module-surface .table-view .column-header, .module-surface .table-view .filler {
                -fx-background-color: transparent;
                -fx-size: 36px;
            }
            .module-surface .table-view .column-header .label {
                -fx-alignment: center-left;
                -fx-padding: 0 8;
                -fx-text-fill: #285341;
            }
            .module-surface .table-cell { -fx-padding: 6 8; -fx-border-color: transparent; }
            .module-surface .table-row-cell:filled:odd, .module-surface .list-cell:filled:odd {
                -fx-background-color: #f6f9f7;
            }
            .module-surface .table-row-cell:filled:selected, .module-surface .list-cell:filled:selected {
                -fx-background-color: #28634e;
                -fx-text-background-color: #ffffff;
            }
            .module-surface .tab-pane > .tab-header-area > .tab-header-background { -fx-background-color: #edf4ef; }
            .module-surface .tab { -fx-padding: 5 10; }
            .module-surface .tab:selected { -fx-background-color: #ffffff; }
            .module-surface .text-input, .module-surface .combo-box-base {
                -fx-background-color: #ffffff;
                -fx-background-insets: 0;
                -fx-background-radius: 7;
                -fx-border-color: #cddbd2;
                -fx-border-radius: 7;
                -fx-prompt-text-fill: #62766a;
            }
            .module-surface .text-input:focused, .module-surface .combo-box-base:focused {
                -fx-border-color: #4a896e;
            }
            .module-surface .combo-box > .list-cell {
                -fx-background-color: transparent;
                -fx-text-fill: #19362e;
            }
            .module-surface .combo-box-base > .arrow-button {
                -fx-background-color: #edf4ef;
                -fx-background-insets: 0;
                -fx-background-radius: 0 6 6 0;
            }
            .module-surface .combo-box-base > .arrow-button > .arrow { -fx-background-color: #315c4a; }
            .module-surface .date-picker > .text-field { -fx-border-color: transparent; }
            .module-surface .split-pane { -fx-background-color: #e5eee8; -fx-padding: 0; }
            .module-surface .split-pane-divider { -fx-background-color: #dbe6de; -fx-padding: 0 2; }
            .sale-section {
                -fx-padding: 12;
                -fx-background-color: #f5f8f6;
                -fx-background-radius: 9;
                -fx-border-color: #e0eae3;
                -fx-border-radius: 9;
            }
            """.getBytes(StandardCharsets.UTF_8));
}