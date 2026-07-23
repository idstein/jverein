package de.jost_net.JVerein.gui.parts;

import java.util.List;

import de.jost_net.JVerein.util.BuchungHistoryMatcher.Proposal;
import de.willuhn.jameica.gui.Action;
import de.willuhn.jameica.gui.formatter.Formatter;
import de.willuhn.jameica.gui.parts.Column;

public class ProposalListTablePart extends JVereinTablePart {

  public ProposalListTablePart(List<Proposal> list, Action action) {
    super(list, action);

    addColumn(new Column("proposedBuchungsartLabel", "Buchungsart", null, false, Column.ALIGN_LEFT));

    try {
      if ((Boolean) de.jost_net.JVerein.Einstellungen.getEinstellung(de.jost_net.JVerein.Einstellungen.Property.BUCHUNGSKLASSEINBUCHUNG)) {
        addColumn(new Column("proposedBuchungsklasseLabel", "Buchungsklasse", null, false, Column.ALIGN_LEFT));
      }
      if ((Boolean) de.jost_net.JVerein.Einstellungen.getEinstellung(de.jost_net.JVerein.Einstellungen.Property.PROJEKTEANZEIGEN)) {
        addColumn(new Column("proposedProjektLabel", "Projekt", null, false, Column.ALIGN_LEFT));
      }
    } catch (Exception e) {
      de.willuhn.logging.Logger.error("Fehler beim Prüfen der Einstellungen in ProposalListTablePart", e);
    }

    addColumn(new Column("formattedScore", "Score", new Formatter() {
      @Override
      public String format(Object val) {
        if (val instanceof Double) {
          return Math.round((Double) val) + "%";
        }
        return val != null ? val.toString() : "";
      }
    }, false, Column.ALIGN_RIGHT));
    addColumn(new Column("displayReason", "Grund", null, false, Column.ALIGN_LEFT));
  }
}
