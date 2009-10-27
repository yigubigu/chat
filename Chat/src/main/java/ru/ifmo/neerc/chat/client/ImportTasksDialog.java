/*
   Copyright 2009 NEERC team

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
/*
 * Date: Oct 24, 2007
 *
 * $Id$
 */
package ru.ifmo.neerc.chat.client;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.StringReader;
import javax.swing.*;

import ru.ifmo.ips.config.*;
import ru.ifmo.neerc.chat.message.TaskMessage;
import ru.ifmo.neerc.chat.task.*;
import ru.ifmo.neerc.chat.user.UserEntry;
import ru.ifmo.neerc.chat.user.UserRegistry;

/**
 * <code>ImportTasksDialog</code> class
 *
 * @author Matvey Kazakov
 */
public class ImportTasksDialog extends JDialog {
    private Chat clientReader;

    public ImportTasksDialog(Frame owner, Chat clientReader) throws HeadlessException {
        super(owner, "Import task batch", true);
        this.clientReader = clientReader;
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

        final JTextArea textArea = new JTextArea(30, 80);
        mainPanel.add(new JScrollPane(textArea));

        JPanel btnPanel = new JPanel();
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
        JButton btnOK = new JButton("OK");
        JButton btnCancel = new JButton("Cancel");
        btnPanel.add(Box.createHorizontalGlue());
        btnPanel.add(btnOK);
        btnPanel.add(Box.createHorizontalStrut(10));
        btnPanel.add(btnCancel);
        btnOK.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                String text = textArea.getText();
                setVisible(false);
                importTasks(text);
                dispose();
            }
        });
        btnCancel.addActionListener(new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                setVisible(false);
                dispose();
            }
        });
        btnPanel.add(Box.createHorizontalGlue());
        mainPanel.add(Box.createVerticalStrut(10));
        mainPanel.add(btnPanel);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setContentPane(mainPanel);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(owner);
    }

    private void importTasks(String text) {
        try {
            TaskRegistry registry = TaskRegistry.getInstance();
            Config config = new XMLConfig(new StringReader(text));
            Config[] list = config.getNodeList("task");
            for (Config taskNode : list) {
                String description = taskNode.getProperty("@desc");
                int type = convertType(taskNode.getProperty("@type"));
                Task task = registry.createTask(description, type);
                clientReader.write(new TaskMessage(TaskMessage.Type.CREATE, -1, task, null));
                Config[] assignNodes;
                try {
                    assignNodes = taskNode.getNodeList("assign");
                } catch (ConfigException e) {
                    assignNodes = new Config[0];
                }
                for (Config assignNode : assignNodes) {
                    String userName = assignNode.getProperty("@user", null);
                    String groupName = assignNode.getProperty("@group", null);
                    if (userName != null) {
                        UserEntry user = UserRegistry.getInstance().findByName(userName);
                        if (user != null) {
                            clientReader.write(new TaskMessage(TaskMessage.Type.ASSIGN, user.getId(), task, null));
                        }
                    } else if (groupName != null) {
                        UserEntry[] users = UserRegistry.getInstance().findByGroupName(groupName);
                        for (UserEntry user : users) {
                            clientReader.write(new TaskMessage(TaskMessage.Type.ASSIGN, user.getId(), task, null));
                        }
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not import tasks (see details in log file):" + e.getMessage(),
                    "Error importing tasks", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace();
        }
    }

    /**
     * @param typeName string task type identifier
     * @return type identifier
     * @throws IllegalArgumentException in case type could not recognized
     * @throws NullPointerException     if typeName is null
     */
    private int convertType(String typeName) {
        String s = typeName.toUpperCase();
        if (s.startsWith("T")) {
            return TaskFactory.TASK_TODO;
        } else if (s.startsWith("R")) {
            return TaskFactory.TASK_REASON;
        } else if (s.startsWith("C")) {
            return TaskFactory.TASK_CONFIRM;
        } else if (s.startsWith("Q")) {
            return TaskFactory.TASK_QUESTION;
        }
        throw new IllegalArgumentException("Unknown type identifier: " + typeName);
    }
}
