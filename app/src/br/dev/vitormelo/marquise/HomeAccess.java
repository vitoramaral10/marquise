package br.dev.vitormelo.marquise;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * What a screen reader (TalkBack) sees of a view that is drawn on a canvas: one virtual node for each thing on screen
 * that can be chosen, with its name, where it is and what OK does on it. The view says what is there ({@link Source});
 * nothing here runs until an accessibility service asks, so with the screen reader off it costs nothing.
 */
final class HomeAccess extends AccessibilityNodeProvider {
    /** One thing on screen a person can choose. Ids are the view's own and stay the same for the same thing. */
    static final class Node {
        final int id;
        final String text;
        final RectF rect;      // in the view's own coordinates
        boolean selected;      // a tab that is the current one
        Runnable click;        // what OK does
        Runnable longClick;    // what holding OK does (the app's menu), for a reader that has no way to hold a key
        Runnable focus;        // the screen reader moved onto it: the view's own focus follows

        Node(int id, String text, RectF rect) {
            this.id = id;
            this.text = text;
            this.rect = rect;
        }
    }

    /** The view: what is on screen now to be chosen (the open overlay, or the main screen), in reading order. */
    interface Source {
        List<Node> nodes();
    }

    private final View host;
    private final Source source;
    private final AccessibilityManager manager;
    private int accessibilityFocus = View.NO_ID;

    HomeAccess(View host, Source source) {
        this.host = host;
        this.source = source;
        this.manager = (AccessibilityManager) host.getContext().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
    }

    /**
     * Whether a screen reader (or another accessibility service) is on: nothing is built or sent otherwise. Marquise's
     * own RemoteKeys doesn't count: it only takes the remote's buttons.
     */
    boolean on() {
        if (manager == null || !manager.isEnabled()) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo info
                : manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (!RemoteKeys.SERVICE.equals(info.getId()) && !(HomeMode.PKG + "/.RemoteKeys").equals(info.getId())) return true;
        }
        return false;
    }

    @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
        if (virtualViewId == View.NO_ID) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host);
            host.onInitializeAccessibilityNodeInfo(info);
            for (Node n : source.nodes()) info.addChild(host, n.id);
            return info;
        }
        Node n = find(virtualViewId);
        if (n == null) return null;
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain(host, n.id);
        info.setPackageName(host.getContext().getPackageName());
        info.setClassName(android.widget.Button.class.getName());
        info.setParent(host);
        info.setText(n.text);
        info.setContentDescription(n.text);
        Rect inParent = new Rect();
        n.rect.roundOut(inParent);
        info.setBoundsInParent(inParent);
        int[] at = new int[2];
        host.getLocationOnScreen(at);
        Rect onScreen = new Rect(inParent);
        onScreen.offset(at[0], at[1]);
        info.setBoundsInScreen(onScreen);
        info.setEnabled(true);
        info.setVisibleToUser(true);
        info.setFocusable(true);
        info.setClickable(n.click != null);
        info.setLongClickable(n.longClick != null);
        info.setSelected(n.selected);
        info.setAccessibilityFocused(n.id == accessibilityFocus);
        if (n.click != null) info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (n.longClick != null) info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        info.addAction(n.id == accessibilityFocus ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        return info;
    }

    @Override public boolean performAction(int virtualViewId, int action, Bundle arguments) {
        if (virtualViewId == View.NO_ID) return host.performAccessibilityAction(action, arguments);
        Node n = find(virtualViewId);
        if (n == null) return false;
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (accessibilityFocus == n.id) return false;
                accessibilityFocus = n.id;
                send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, n);
                if (n.focus != null) n.focus.run();
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (accessibilityFocus != n.id) return false;
                accessibilityFocus = View.NO_ID;
                send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, n);
                return true;
            case AccessibilityNodeInfo.ACTION_CLICK:
                if (n.click == null) return false;
                n.click.run();
                return true;
            case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                if (n.longClick == null) return false;
                n.longClick.run();
                return true;
            default:
                return false;
        }
    }

    @Override public AccessibilityNodeInfo findFocus(int focus) {
        if (focus != AccessibilityNodeInfo.FOCUS_ACCESSIBILITY || accessibilityFocus == View.NO_ID) return null;
        return createAccessibilityNodeInfo(accessibilityFocus);
    }

    @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text, int virtualViewId) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (Node n : source.nodes()) {
            if (n.text.toLowerCase(java.util.Locale.ROOT).contains(lower)) out.add(createAccessibilityNodeInfo(n.id));
        }
        return out;
    }

    /** What is on screen changed (another tab, an overlay opened, new posters): the reader asks again. */
    void changed() {
        if (!on() || host.getParent() == null) return;
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        e.setPackageName(host.getContext().getPackageName());
        e.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        e.setSource(host);
        host.getParent().requestSendAccessibilityEvent(host, e);
    }

    /** The view's own focus moved (arrows, a remote without the reader's navigation): say where it is now. */
    void focusMoved(int id) {
        Node n = on() ? find(id) : null;
        if (n == null) return;
        accessibilityFocus = id;
        send(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, n);
    }

    private Node find(int id) {
        for (Node n : source.nodes()) if (n.id == id) return n;
        return null;
    }

    private void send(int type, Node n) {
        if (!on() || host.getParent() == null) return;
        AccessibilityEvent e = AccessibilityEvent.obtain(type);
        e.setPackageName(host.getContext().getPackageName());
        e.setClassName(android.widget.Button.class.getName());
        e.setSource(host, n.id);
        e.getText().add(n.text);
        host.getParent().requestSendAccessibilityEvent(host, e);
    }
}
