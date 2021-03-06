package edu.virginia.vcgr.genii.client.gui.exportdir;

import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

import edu.virginia.vcgr.genii.client.gui.GuiHelpAction;
import edu.virginia.vcgr.genii.client.gui.GuiUtils;
import edu.virginia.vcgr.genii.client.gui.HelpLinkConfiguration;
import edu.virginia.vcgr.genii.client.rns.RNSPath;
import edu.virginia.vcgr.genii.client.rns.RNSPathQueryFlags;
import edu.virginia.vcgr.genii.client.utils.flock.FileLockException;

public class ExportCreationDialog extends JDialog
{
	static final long serialVersionUID = 0L;

	static private final String _TITLE = "Export Creation";

	private ResourcePathsWidget _paths;

	private ExportCreationInformation _information = null;
	private JRadioButton _standardExportService = null;
	private JRadioButton _lightweightExportService = null;

	public ExportCreationDialog(JDialog owner, String ContainerPath, String TargetPath) throws FileLockException, NoContainersException
	{
		super(owner);

		Container container;

		setTitle(_TITLE);
		container = getContentPane();

		container.setLayout(new GridBagLayout());
		/*
		 * container.add(_deployments = new DeploymentsWidget(), new GridBagConstraints(0, GridBagConstraints.RELATIVE, 2, 1, 1.0, 0.0,
		 * GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL, new Insets(5, 5, 5, 5), 5, 5));
		 */
		container.add(_standardExportService = new JRadioButton("Standard Export"),
			new GridBagConstraints(0, 0, 2, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 0, 0));
		container.add(_lightweightExportService = new JRadioButton("Light-weight Export"),
			new GridBagConstraints(0, 1, 2, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
		container.add(_paths = new ResourcePathsWidget(true, true, ContainerPath, TargetPath),
			new GridBagConstraints(0, 2, 2, 1, 1.0, 1.0, GridBagConstraints.WEST, GridBagConstraints.BOTH, new Insets(5, 5, 5, 5), 5, 5));

		_lightweightExportService.setSelected(true);
		_standardExportService.setSelected(false);
		ButtonGroup group = new ButtonGroup();
		group.add(_lightweightExportService);
		group.add(_standardExportService);

		CreateExportAction action = new CreateExportAction();

		_paths.addInformationListener(action);

		container.add(createButtonPanel(owner, action), new GridBagConstraints(0, 3, 2, 1, 1.0, 0.0, GridBagConstraints.CENTER,
			GridBagConstraints.HORIZONTAL, new Insets(5, 5, 5, 5), 5, 5));
	}

	private Component createButtonPanel(JDialog owner, Action action)
	{
		JPanel panel = new JPanel(new GridBagLayout());

		panel.add(new JButton(action),
			new GridBagConstraints(0, 0, 1, 1, 0.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
		panel.add(new JButton(new CancelAction()),
			new GridBagConstraints(1, 0, 1, 1, 0.0, 1.0, GridBagConstraints.CENTER, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));
		panel.add(new JButton(new GuiHelpAction(owner, HelpLinkConfiguration.get_help_url(HelpLinkConfiguration.EXPORT_CREATION_HELP))),
			new GridBagConstraints(2, 0, 1, 1, 1.0, 1.0, GridBagConstraints.EAST, GridBagConstraints.NONE, new Insets(5, 5, 5, 5), 5, 5));

		return panel;
	}

	private boolean hasEnoughInformationToCreate()
	{
		String localPath = _paths.getLocalPath();
		String rnsPath = _paths.getRNSPath();
		String containerPath = _paths.getContainerPath();

		return localPath != null && localPath.trim().length() > 0 && rnsPath != null && rnsPath.trim().length() > 0 && containerPath != null
			&& containerPath.trim().length() > 0;
	}

	public ExportCreationInformation getExportCreationInformation()
	{
		return _information;
	}

	private class CreateExportAction extends AbstractAction implements IInformationListener
	{
		static final long serialVersionUID = 0L;

		public CreateExportAction()
		{
			super("Create Export");

			setEnabled(hasEnoughInformationToCreate());
		}

		@Override
		public void actionPerformed(ActionEvent e)
		{

			try {
				RNSPath rnsPath = RNSPath.getCurrent().lookup(_paths.getRNSPath(), RNSPathQueryFlags.MUST_NOT_EXIST);
				ExportManipulator.validate(rnsPath);
				_information = new ExportCreationInformation(_paths.getContainerPath(), _paths.getLocalPath(), _paths.getRNSPath(),
					_lightweightExportService.isSelected());
				setVisible(false);
			} catch (Throwable cause) {
				GuiUtils.displayError((Component) e.getSource(), "Export Creation Error", cause);
			}

		}

		@Override
		public void updateInformation()
		{
			setEnabled(hasEnoughInformationToCreate());
		}
	}

	private class CancelAction extends AbstractAction
	{
		static final long serialVersionUID = 0L;

		public CancelAction()
		{
			super("Cancel");
		}

		@Override
		public void actionPerformed(ActionEvent e)
		{
			_information = null;
			setVisible(false);
		}
	}
}
