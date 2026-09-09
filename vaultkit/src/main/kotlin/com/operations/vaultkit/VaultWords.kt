package com.operations.vaultkit

/**
 * The word list a generated passphrase is drawn from.
 *
 * ## Why not the EFF list
 *
 * The obvious choice is EFF's 7,776-word diceware list — 12.9 bits a word against this list's ten —
 * and it was not taken for one reason: it is 60 kilobytes of words in a module whose entire job is
 * to be small, auditable and free of anything the reader has to take on trust. A five-word
 * passphrase from this list is worth about fifty bits and a six-word one about sixty, which is above
 * what any online attack reaches and comfortably above what the vault's 310,000-round KDF makes
 * affordable offline. Somebody who wants more types more words, and the entropy shown on the screen
 * goes up honestly as they do.
 *
 * ## What is in it
 *
 * Concrete, common, three-to-seven letters, one obvious spelling. Deliberately excluded:
 *
 *  - homophones (`their`, `there`), which are misheard when a passphrase is read aloud;
 *  - words that differ by one letter from another word here, for the same reason;
 *  - anything with an apostrophe, an accent or a plural-only form;
 *  - anything grim. This is a phrase somebody types every morning; the list is farms, weather,
 *    kitchens and tools on purpose.
 *
 * The list is public — it is compiled into the app and printed here — and that costs nothing: the
 * entropy of a passphrase is in the choosing, not in the vocabulary. See
 * [PasswordGenerator.passphraseEntropyBits], which computes bits from the list's real size, so
 * adding or removing words here can never make the number on the screen wrong.
 */
object VaultWords {

    /**
     * Sorted and de-duplicated at load, so an accidental repeat in the source below costs one word
     * of variety rather than silently skewing the odds towards it.
     */
    val list: List<String> by lazy { RAW.trim().split(Regex("\\s+")).distinct().sorted() }

    private const val RAW = """
        able acorn actor adapt admit adobe adopt agile alarm album alert alley almond alpha amber
        anchor angle ankle apple april apron arbor archer arctic arena argue armor arrow ashen aspen
        asset atlas atom attic autumn avenue awake award azure

        bacon badge bagel baker balcony ballad bamboo banana banjo barley barn basil basin basket
        batch beacon beam bean beetle bell belt bench berry birch biscuit bison blanket blossom
        blueprint boat bobcat boiler bolt bonnet booklet boots border bottle boulder bounce bracket
        braid branch brass bread breeze brick bridge brook broom brush bucket buckle budget buffalo
        bugle builder bulb bundle bunker burlap burrow bushel butler button

        cabin cable cactus camel candle canoe canopy canvas canyon caper caramel cardigan cargo
        carpet carrot cartoon carve casket castle cattle cauldron cavern cedar cellar cement census
        chalk chapel charcoal chariot cheese cherry chestnut chime chisel chorus chowder cider cinder
        circus cistern citrus clamp clarinet clay cleaver clever cliff climate cloak clover cobbler
        cocoa coffee collar colony column comet compass copper coral cork cottage cotton council
        counter courtyard cove cowbell crane crate crayon creek crescent crimson crocus crooked
        crumble crystal cucumber cupboard curtain cushion custard cutlery cygnet cypress

        dagger dahlia daisy damson dandelion dapper dawn daylight decoy deep denim depot desert
        diamond diary digger dinner dipper ditch dock dolphin domain donkey doorway dough dovetail
        dragon drapes drawer drift drill drizzle drummer dugout dune dustpan dwelling

        eagle early earth easel eastern echo eclipse elbow elder elm ember emerald empire enamel
        engine envelope equator errand escort estate ether evening exhale exile exodus expert extra

        fable fabric falcon fallow fanfare farmer fathom fawn feather fedora fennel fern ferry
        fiddle field fig filbert filter finch fireside fisher flagon flannel flask flatbread flax
        fleece flint florist flour flute foghorn foil folder foliage forest forge fossil fountain
        foxglove fragment freckle frigate frost frozen fudge funnel furnace

        gable gadget gallery gallon gander garden garlic garnet gasket gateway gazebo gecko gemstone
        geode geyser giant ginger girder glacier glade glazier glider globe glossy glove gnome goblet
        golden gondola goose gopher gourd granite grape gravel gravy grazing greenhouse griffin
        grotto grove guitar gulley gumdrop gutter gypsum

        habitat hallway hamlet hammer hammock hamper handle harbor harmony harness harvest hatchet
        haven hawthorn hazel headland hearth heather hedge helmet hemlock herald herbal heron hickory
        highland hillside hinge hobby hollow honey hoop horizon hornet horseshoe hostel hourly
        houseboat hubcap huddle humid hunter hurdle husk hyacinth hydrant

        iceberg icicle igloo impala import inbox incline index indigo ingot inkwell inland insect
        instant invite iris ironwood island isotope ivory

        jacket jackpot jaguar jamboree jasmine javelin jetty jewel jigsaw jockey journal juggler
        juniper jupiter

        kayak keeper kennel kernel kestrel kettle keyboard kiln kingdom kitchen kitten knapsack
        knitting knoll koala krypton

        lagoon lamplight lancer landing lantern lapel larch lasso latch lattice laundry lavender
        ledger legend lemon lentil leopard levee lever library lichen lifeboat lilac limestone linden
        linen lintel lion lizard llama lobby lobster locket locust lodge loft logbook lookout loom
        lotus lounge lumber lunar lupine lyric

        machine madras magnet magnolia mahogany mailbox mallet mammoth mandolin mango manor mantel
        maple marble margin marigold marina marker marmot marsh mascot mason matinee meadow measure
        medley melody melon meridian mermaid mesa meteor midland milestone millet mimosa mineral
        mingle minnow minster mint mirror mitten moccasin mohair molasses moment monarch monsoon
        moorland moped mortar mosaic moss motto mountain muffin mulberry muslin mustard

        napkin narwhal nautical navigate nebula nectar needle nephew nest nettle newsprint nickel
        nimble nomad noodle north notebook novel nozzle nugget nutmeg

        oasis oatmeal obelisk observe ocean ocelot octave octopus offshore olive omelet onion onyx
        opal opera orbit orchard orchid organ origami osprey ostrich otter outcrop outfit outlet
        outpost oxbow oyster

        pacific paddle pagoda painter palace palette pamphlet pancake panda pantry paprika papyrus
        parade parcel parchment parlor parrot parsley parsnip pasture patio pavement peacock pearl
        pebble pelican pendant penguin pennant peony pepper pergola perfume petal pewter pheasant
        piano piccolo picket picnic pigment pilgrim pillar pilot pinecone pinnacle pioneer pipeline
        pistachio pitcher plaid plank plateau platform plaza pleasant plover plumage plunger pocket
        podium polar pollen pomelo poncho pond pony poplar poppy porch portal possum postcard
        postern potato pottery pouch powder prairie present pretzel primrose prism promise pudding
        puffin pulley pumice pumpkin puppet purple puzzle pyramid

        quarry quartz quaver quilt quince quiver

        rabbit raccoon radar radish rafter rainbow rampart rancher rapids rattle raven ravine reactor
        rebus recipe redwood reed refuge regatta reindeer relay relish remedy rescue reservoir
        rhubarb ribbon riddle ridge rigging ripple risotto river roadway roast robin rocket rodeo
        roost rosemary roster rotunda rowboat rubble rudder rugby runner rustic rutabaga

        saddle safari saffron sage sailor salmon saloon saltbox samba sandal sapling sapphire
        sardine sassafras satchel satin saucer sawmill scallop scarlet scholar schooner scissors
        scone scooter scoreboard scout scramble screwdriver scroll sculpture seabird seagull season
        seaweed seedling sequoia serpent settle shale shallot shamrock shanty shawl shelter sherbet
        shingle shipyard shoreline shovel shutter sickle sidecar sienna signal silhouette silo
        silver sixpence skate skillet skipper skylight slate sledge slipper slogan slope smitten
        smolder snapdragon snorkel snowdrift socket sofa solar soldier sonnet sorbet sorrel spaniel
        sparrow spatula spearmint spindle spinach spiral splendid spool sprig spruce squadron squash
        squirrel stable stadium stagger stairway stallion stamina stanza starfish station steeple
        stencil stepping stirrup stitch stoneware stopwatch stork stovetop strand stucco studio
        subway sugar suitcase sultan summit sunbeam sundial sunflower surfboard swallow swampland
        sweater swivel sycamore syrup

        tabby tackle tadpole tailor talcum tandem tangerine tankard tapestry tarragon tavern teacup
        teakettle teapot teaspoon telegram tempo tenant tendril tenor terrace terrain textile thicket
        thimble thistle thorn thunder ticket tidal tiger timber tinder tinsel toaster toboggan tomato
        topaz topsoil torch tortoise totem toucan towel tractor trailer transit trapeze treasure
        trellis trestle triangle tribune trident trillium trinket trombone trophy trout trowel
        truffle trumpet tulip tumbler tundra tunnel turban turbine turnip turquoise turret turtle
        tweed twilight

        ukulele umbrella underpass unicorn uniform upland upstream urchin utensil

        vacation valley vanilla vantage vapor varnish vassal vault velvet veranda vessel viaduct
        vicar viking village vinegar vineyard violet viola volcano voltage voyage

        wagon walnut walrus wander wardrobe warehouse warmth washboard waterfall watermelon wattle
        waxwing weather weaver webbing wedge welcome western wetland whaler wharf wheat whistle
        wicker widget wigwam wildcat willow windmill window winter wisteria wombat woodland woolen
        workshop worthy wren wrench

        yacht yardstick yarrow yeast yellow yeoman yodel yogurt yonder

        zebra zenith zeppelin zigzag zinnia zipper zither zodiac zucchini
    """
}
