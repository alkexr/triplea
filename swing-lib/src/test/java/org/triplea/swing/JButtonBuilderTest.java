package org.triplea.swing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.event.ActionEvent;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.Runnables;

class JButtonBuilderTest {

  @Test
  void title() {
    final String value = "testing title";
    final JButton button = JButtonBuilder.builder()
        .title(value)
        .actionListener(Runnables.doNothing())
        .build();
    assertThat(button.getText(), is(value));
  }

  @Test
  void checkActionListener() {
    // button action will be to add one to our integer, we'll fire the button action and verify we get the +1
    final AtomicInteger integer = new AtomicInteger(0);
    final JButton button = JButtonBuilder.builder()
        .title("title")
        .actionListener(integer::incrementAndGet)
        .toolTip("toolTip")
        .build();

    Arrays.stream(button.getActionListeners())
        .forEach(listener -> listener.actionPerformed(new ActionEvent(new Object(), 0, "")));
    assertThat(integer.get(), is(1));

    assertThat(button.getToolTipText(), is("toolTip"));
  }

  @Test
  void titleCannotBeEmpty() {
    assertThrows(IllegalArgumentException.class, () -> JButtonBuilder.builder().title(""));
  }

  @Test
  void titleIsRequired() {
    assertThrows(NullPointerException.class, () -> JButtonBuilder.builder()
        .actionListener(Runnables.doNothing())
        .build());
  }

  @Test
  void actionListenerIsRequired() {
    assertThrows(NullPointerException.class, () -> JButtonBuilder.builder().actionListener((Runnable) null));
  }

  @Test
  void toolTipCanNotBeEmptyIfSpecified() {
    assertThrows(IllegalArgumentException.class, () -> JButtonBuilder.builder().toolTip(""));
  }
}
