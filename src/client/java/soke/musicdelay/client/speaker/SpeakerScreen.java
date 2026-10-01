package soke.musicdelay.client.speaker;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import soke.musicdelay.speaker.SpeakerNetworking;
import java.util.*;

public final class SpeakerScreen extends Screen {
    private SpeakerNetworking.Menu state;
    private Button power,main,choose,prev,next;
    private EditBox search;
    private List<SpeakerTracks.Entry> tracks=List.of();
    private int index;
    private List<SpeakerTracks.Entry> previousLibrary;
    private String previousQuery="";
    public SpeakerScreen(SpeakerNetworking.Menu state) { super(Component.literal("Деревянная колонка")); this.state=state; }
    public boolean accepts(UUID token) { return state.token().equals(token); }
    public void update(SpeakerNetworking.Menu value) { state=value; refresh(); }
    private boolean available() { return Minecraft.getInstance().hasSingleplayerServer() && state.canConfigure(); }
    @Override protected void init() {
        int x=width/2,y=height/2;
        power=addRenderableWidget(Button.builder(Component.empty(),b->send(0,"" )).bounds(x-100,y-62,160,20).build());
        main=addRenderableWidget(Button.builder(Component.literal("M"),b->send(1,"")).bounds(x+72,y-62,20,20).build());
        search=addRenderableWidget(new EditBox(font,x-110,y-13,220,20,Component.literal("Поиск трека")));
        search.setMaxLength(100); search.setHint(Component.literal("Поиск в своей музыке"));
        search.setResponder(q->index=0);
        prev=addRenderableWidget(Button.builder(Component.literal("<"),b->{if(!tracks.isEmpty())index=Math.floorMod(index-1,tracks.size());}).bounds(x-110,y+12,24,20).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"),b->{if(!tracks.isEmpty())index=(index+1)%tracks.size();}).bounds(x+86,y+12,24,20).build());
        choose=addRenderableWidget(Button.builder(Component.literal("Играть выбранный трек"),b->{if(!tracks.isEmpty())send(2,tracks.get(index).key());}).bounds(x-110,y+38,220,20).build());
        addRenderableWidget(Button.builder(Component.literal("Готово"),b->onClose()).bounds(x-50,y+88,100,20).build());
        SpeakerTracks.refresh(); refresh();
    }
    @Override public void tick() {
        super.tick();
        String q=search.getValue().toLowerCase(Locale.ROOT);
        var library=SpeakerTracks.list();
        if (library!=previousLibrary || !q.equals(previousQuery)) {
            tracks=library.stream().filter(t->t.name().toLowerCase(Locale.ROOT).contains(q)).toList();
            previousLibrary=library;previousQuery=q;
        }
        index=tracks.isEmpty()?0:Math.min(index,tracks.size()-1); refresh();
    }
    private void refresh() {
        if(power==null)return;
        power.setMessage(Component.literal(state.enabled()?"Выключить":"Включить"));
        main.setMessage(Component.literal(state.main()?"§aM":"M"));main.active=state.canConfigure();
        choose.active=available()&&!tracks.isEmpty();prev.active=next.active=available()&&tracks.size()>1;
        search.active=available();
    }
    private void send(int action,String track) {
        if(ClientPlayNetworking.canSend(SpeakerNetworking.Action.TYPE))ClientPlayNetworking.send(new SpeakerNetworking.Action(state.token(),action,track));
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        super.extractRenderState(g,mouseX,mouseY,delta);int x=width/2,y=height/2;
        g.centeredText(font,title,x,y-100,0xFFFFFFFF);
        g.centeredText(font,Component.literal(state.enabled()?"Включена":"Выключена"),x,y-81,0xFFDDDDDD);
        var current=SpeakerTracks.find(state.track());
        String label=state.track().isEmpty()?"Трек не выбран":current==null?"Трек отсутствует в библиотеке":current.name();
        g.centeredText(font,Component.literal(font.plainSubstrByWidth(label,240)),x,y-32,0xFFDDDDDD);
        String selected=tracks.isEmpty()?"Нет подходящих треков":tracks.get(index).name();
        g.centeredText(font,Component.literal(font.plainSubstrByWidth(selected,160)),x,y+18,0xFFFFFFFF);
        String note=!Minecraft.getInstance().hasSingleplayerServer()?"Звук пока доступен в одиночной игре":!state.placed()?"Переносное звучание • Main или последняя колонка":"Один трек • без повтора • дальность до 24 блоков";
        g.centeredText(font,Component.literal(note),x,y+66,0xFFBBBBBB);
        g.centeredText(font,Component.literal("Main: "+(state.main()?"да":"нет")+"  •  ID: "+state.speakerId().toString().substring(0,8)),x,y+77,0xFF999999);
    }
}
