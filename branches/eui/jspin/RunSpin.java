/* Copyright 2003-4 by Mordechai (Moti) Ben-Ari. See copyright.txt. */
/*
* Fork process for running Spin
* Manage dialog for interactive simulation
*/

package jspin;
import javax.swing.*;
import java.io.*;
import java.awt.event.*;
import java.util.StringTokenizer;

class RunSpin {
  private Editor editor;               // The editor object
  private Filter filter;               // The filter object
  private RunThread runThread;         // Thread object for running Spin
  private SelectDialog selectDialog;   // Thread object for select dialog

  private JTextArea messageArea;       // The message area
  private JTextArea area;              // Output area display

  private String command, parameters;  // The command being executed
  private jSpin.FilterTypes filtering; // Output filtering mode

  // For interactive mode
  // (Executable) transitions and selections in popup menu
  private static final int MAX_SELECTIONS = 50;
  private String[] selections = new String[MAX_SELECTIONS];
  private int numSelections;

  RunSpin(Editor e, JTextArea m, Filter f) {
    editor = e;
    messageArea = m;
    filter = f;
  }

  // Called by jSpin to run Spin
  void run(JTextArea area, jSpin.FilterTypes filtering, String command, String parameters) {
    this.filtering = filtering;
    this.area = area;
    this.area.setText("");
    this.command = command;
    this.parameters = parameters;
    filter.init(Config.properties);
    // Make sure that a file has been opened
    if (editor.file == null) {
        jSpin.append(messageArea, Config.OPEN_FILE);
        return;
    }
    // Save editor buffer and set up display areas
    editor.saveFile(null);
    if (area != messageArea)
        this.area.setText("");
    jSpin.append(messageArea, command + " " + parameters + " ... ");
    // Create a thread to run Spin and start it
    runThread = new RunThread();
    runThread.start();
  }

  // Run Spin and wait for it to complete
  void runAndWait(JTextArea area, jSpin.FilterTypes filtering, String command, String parameters) {
    run(area, filtering, command, parameters);
    if (runThread == null) return; // If file not open
    try {
        runThread.join();
        jSpin.append(messageArea, "done!\n");
    } catch (InterruptedException e) {}
  }

  // Check is Spin is still running
  boolean isRunning() {
    return (runThread != null) && runThread.isAlive();
  }

  // Kill the thread running Spin and the selection dialog
  void killSpin() {
    if (runThread != null) {
        runThread.killSpin();
        runThread.interrupt();
        runThread = null;
    }
    if (selectDialog != null) {
        selectDialog.disposeDialog();
        selectDialog.interrupt();
        selectDialog = null;
    }
    jSpin.append(messageArea, "\nSpin process stopped\n");
  }

  // Class RunThread enables Spin to run asynchronously
  private class RunThread extends Thread {
    private Process p;

    public void run() {
      try {
        // Use ProcessBuilder to run Spin, redirecting ErrorStream
        String[] sa = stringToArray(command, parameters);
        ProcessBuilder pb = new ProcessBuilder(sa);
        File pf = editor.file.getParentFile();
        if (pf != null) pb.directory(pf.getCanonicalFile());
        pb.redirectErrorStream(true);
        p = pb.start();
        // Connect to I/O streams
        InputStream istream = p.getInputStream();
        BufferedReader input =
          new BufferedReader(new InputStreamReader(istream));
        OutputStream ostream = p.getOutputStream();
        OutputStreamWriter output = new OutputStreamWriter(ostream);
        // Process Spin output line by line
        String s = "";
        boolean running = true;
        boolean chosenFlag = false;
        String  currentState = "";
        while (running) {
          s = input.readLine();
          System.out.println(s);
          if (s == null)
            running = false;
          else if (filtering == jSpin.FilterTypes.SIMULATION)
            jSpin.append(area, filter.filterSimulation(s));
          else if (filtering == jSpin.FilterTypes.INTERACTIVE) {
            if (s.startsWith("initial state=") ||s.startsWith("next state=")) {
              currentState = s;
              numSelections = 0;
            }
            else if (s.startsWith("chosen transition="))
              chosenFlag = true;
            else if (!chosenFlag && s.startsWith("process="))
              selections[numSelections++] =
                Filter.extract(s,       "process=") + " " +
                Filter.extract(s,       "line=")    + " " + 
                Filter.extractBraces(s, "statement=");
            else if (chosenFlag && s.startsWith("process=")) {
              jSpin.append(area, filter.filterSimulation(currentState));
              jSpin.append(area, filter.filterSimulation(s));
              chosenFlag = false;
            }
            else if (s.startsWith("choose from=")) {
              filter.storeVariables(currentState);
              running = select(filter.getTitle() + "\n" + filter.variablesToString(false), area, p, input, output);
            }
            else
              jSpin.append(area, filter.filterSimulation(s));
          }
          else if (filtering == jSpin.FilterTypes.VERIFICATION)
            jSpin.append(area, filter.filterVerification(s));
          else
            jSpin.append(area, s + "\n");
        }
        // Wait for Spin process to end
        p.waitFor();
      } catch (InterruptedException e) {
        jSpin.append(messageArea, "Interrupted exception");
      }
      catch (java.io.IOException e) {
        jSpin.append(messageArea, "IO exception\n" + e);
      }
    }

    // String to array of tokens - for ProcessBuilder
    //   Previous versions of jSpin used StringTokenizer
    //     which caused problems in Linux
    String[] stringToArray(String command, String s) {
      char quote = Config.getBooleanProperty("SINGLE_QUOTE") ? '\'' : '"';
      String[] sa = new String[50];
      int count = 0, i = 0, start = 0;
      sa[count] = command;
      count++;
      while (i < s.length() && s.charAt(i) == ' ') i++;
      start = i;
      boolean isQuote;
      do {
        if (i == s.length()) break;
        isQuote = s.charAt(i) == quote;
        if (isQuote) i++;
        if (isQuote) {
          while (s.charAt(i) != quote) i++;
          i++;
        }
        else
          while (i < s.length() && s.charAt(i) != ' ') i++;
        sa[count] = s.substring(start, i);
        while (i < s.length() && s.charAt(i) == ' ') i++;
        start = i;
        count++;
      } while (true);
      String[] sb = new String[count];
      System.arraycopy(sa, 0, sb, 0, count);
      return sb;
    }

    // Kill spin process
    private void killSpin() {
      if (p != null) p.destroy();
    }

    // Select the next statement to run in interactive simulation
    private boolean select(
        String state,
        JTextArea area, Process p, 
        BufferedReader input, OutputStreamWriter output) {
      try {
        // Get selection from dialog
        int selectedValue = -1;
        selectDialog = 
          new SelectDialog(
            numSelections,
            numSelections <= Config.getIntProperty("SELECT_MENU"),
            state,
            selections);
        selectDialog.start();
        while (selectedValue == -1) {
          try {
            Thread.sleep(Config.getIntProperty("POLLING_DELAY"));
          } 
          catch (InterruptedException e) {}
          if (selectDialog == null) break;
          else selectedValue = selectDialog.getValue();
        }
        // For Macintosh (?) - Angelika's problem (?)
        if (selectDialog != null) { 
          selectDialog.interrupt();
          selectDialog = null;
        }
        // If 0 (escape or close) selected, quit Spin interaction
        if (selectedValue == 0) {
            output.write("q\n");
            output.flush();
            return false;
        }
        // Send selection to Spin
        selectedValue--;
        output.write(selectedValue + "\n");
        output.flush();
        return true;
      } catch (Exception e) {
        System.err.println(e);
        killSpin();
        return false;
      }
    }
  }
  
  // Class SelectDialog displays the statement select dialog in a thread
  private class SelectDialog extends Thread implements ActionListener {
    private int selectedValue = -1;
      // Positive when a button is selected, zero upon escape or close
    private int numOptions;     // Number of process buttons
    private String[] selections;// The selections

    private JFrame    dialog;      // The frame
    private JPanel    panel1;
    private JButton[] options;  // Array of process buttons
    private JComboBox pulldown = new JComboBox();
    private int       width;

    // Constructor - set up frame with number of buttons required
    SelectDialog (int numOptions, boolean buttons, String state, String[] selections) {
      this.numOptions = numOptions;
      this.selections = selections;
      dialog = new JFrame();
      dialog.addWindowListener(
        new WindowAdapter() {
        public void windowClosing(WindowEvent e) {
          selectedValue = 0;
          dialog.dispose();
        }
      });
      dialog.getRootPane().registerKeyboardAction(this,
              KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
              JComponent.WHEN_IN_FOCUSED_WINDOW);
      dialog.setTitle(Config.SELECT);
      panel1 = new JPanel();
      if (buttons) constructButtonsDialog(); else constructMenuDialog();
      JTextArea stateField = new JTextArea(state, 2, 50);
      stateField.setFont(messageArea.getFont());
      JPanel panel2 = new JPanel();
      panel2.add(stateField);
      panel1.setLayout(new java.awt.GridLayout(1,1));
      panel2.setLayout(new java.awt.GridLayout(1,1));
      dialog.getContentPane().setLayout(new java.awt.GridLayout(2,1));
      dialog.getContentPane().add(panel2);
      dialog.getContentPane().add(panel1);
      dialog.setSize(width, Config.getIntProperty("SELECT_HEIGHT") * 2);
      dialog.setLocationRelativeTo(messageArea);
      dialog.validate();
      dialog.setVisible(true);
      options[0].requestFocusInWindow();
    }
    
    void constructButtonsDialog() {
      panel1.setLayout(new java.awt.GridLayout(1, numOptions));
      options = new JButton[numOptions];
      JButton button = null;
      for (int i = 1; i <= numOptions; i++) {
        button = new JButton(selections[i-1]);
        button.setFont(messageArea.getFont());
        button.addActionListener(this);
        options[i-1] = button;
        panel1.add(button);
      }
      width = Config.getIntProperty("SELECT_BUTTON") * numOptions;
      // dialog.setSize(
      //   Config.getIntProperty("SELECT_BUTTON") * numOptions,
      //   Config.getIntProperty("SELECT_HEIGHT") * 2);
    }

    void constructMenuDialog() {
      panel1.setLayout(new java.awt.BorderLayout());
      pulldown = new JComboBox();
      pulldown.setFont(messageArea.getFont());
      pulldown.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);
      pulldown.setEditable(false);
      for (int i = 0; i < numOptions; i++)
        pulldown.addItem(selections[i]);
      pulldown.setSelectedIndex(-1);
      pulldown.addActionListener(this);
      panel1.add(pulldown, java.awt.BorderLayout.CENTER);
      width = Config.getIntProperty("SELECT_BUTTON"); 
      // dialog.setSize(
      //   Config.getIntProperty("SELECT_BUTTON"), 
      //   Config.getIntProperty("SELECT_HEIGHT"));
    }

    // Display dialog
    public void run() {
      dialog.setVisible(true);
    }

    // ActionListener
    public void actionPerformed(ActionEvent e) {
      if (e.getSource() instanceof JButton) {
        JButton selected = (JButton) e.getSource();
        for (int i = 0; i < numOptions; i++)
          if (options[i].equals(selected))
            selectedValue = i+1;
      } 
      else if (e.getSource() instanceof JComboBox) {
        selectedValue = ((JComboBox)e.getSource()).getSelectedIndex()+1;
      } 
      else {
        selectedValue = 0;
        dialog.dispose();
        return;
      }
      dialog.dispose();
      java.awt.Dimension rv = ((JComponent)e.getSource()).getSize(null);
      Config.setIntProperty("SELECT_BUTTON", rv.width);
    }
    
    // Dispose of dialog frme
    private void disposeDialog() {
      if (dialog != null) dialog.dispose();
    }
    
    // Get value
    private int getValue() {
      return selectedValue;
    }
  }
}