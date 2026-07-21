/**********************************************************************
 * Copyright (c) 2026 by Heiner Jostkleigrewe and pair partners.
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 **********************************************************************/
package de.jost_net.JVerein.gui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.TabFolder;

import de.jost_net.JVerein.gui.action.DokumentationAction;
import de.jost_net.JVerein.gui.control.KoerperschaftssteuerControl;
import de.willuhn.jameica.gui.AbstractView;
import de.willuhn.jameica.gui.GUI;
import de.willuhn.jameica.gui.parts.ButtonArea;
import de.willuhn.jameica.gui.util.TabGroup;

public class KoerperschaftssteuerView extends AbstractView
{
  private static int tabindex = 0;

  @Override
  public void bind() throws Exception
  {
    GUI.getView().setTitle("Körperschaftssteuer-Assistent");

    final KoerperschaftssteuerControl control = new KoerperschaftssteuerControl(this);

    // Jahr-Auswahl und Stammdaten-Info-Bereich
    control.getFilterPart();

    // Tab-Struktur
    final TabFolder folder = new TabFolder(getParent(), SWT.NONE);
    folder.setLayoutData(new GridData(GridData.FILL_BOTH));

    // Tab 1: Grenzwerte & Warnungen (Audits)
    TabGroup warnungenGroup = new TabGroup(folder, "Steuer-Audits & Warnungen", true, 1);
    control.paintWarnungenTab(warnungenGroup.getComposite());

    // Tab 2: Beleg- & Spenden-Audits
    TabGroup belegeGroup = new TabGroup(folder, "Belege & Spenden", true, 1);
    control.paintBelegeTab(belegeGroup.getComposite());

    // Tab 3: Bereichs-Ergebnisse (EÜR)
    TabGroup ergebnisseGroup = new TabGroup(folder, "Bereichsergebnisse (EÜR)", true, 1);
    control.paintErgebnisseTab(ergebnisseGroup.getComposite());

    // Tab 4: Export-Center
    TabGroup exportGroup = new TabGroup(folder, "DATEV- & PDF-Export", true, 1);
    control.paintExportTab(exportGroup.getComposite());

    if (tabindex < folder.getItemCount())
    {
      folder.setSelection(tabindex);
    }
    folder.addSelectionListener(new SelectionListener()
    {
      @Override
      public void widgetSelected(SelectionEvent evt)
      {
        tabindex = folder.getSelectionIndex();
      }

      @Override
      public void widgetDefaultSelected(SelectionEvent arg0)
      {
        //
      }
    });

    ButtonArea buttons = new ButtonArea();
    buttons.addButton("Hilfe", new DokumentationAction(),
        null, false, "question-circle.png");
    buttons.addButton("Audits aktualisieren", control.getRefreshAction(),
        null, false, "view-refresh.png");
    buttons.paint(this.getParent());
  }
}
